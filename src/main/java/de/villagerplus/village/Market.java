package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.build.Palette;
import de.villagerplus.build.Step;
import de.villagerplus.job.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/** Persistent shopkeeper careers, real hut construction, private stock and conserved villager-to-villager payments. */
public final class Market {
 private Market() {}
 public static final String[] KINDS={"Lebensmittelladen","Textilhandel","Materialladen"};
 static final int STOCK_CAP=16;
 static final int STALL_RENT=1;
 static final int FIRST_STALL_POPULATION=4;
 static final int SECOND_STALL_POPULATION=16;
 static final int THIRD_STALL_POPULATION=24;
 static final int OWNER_REASSIGNMENT_POPULATION=16;
 // Keep preparation finite while allowing the stand footprint to cut rooted trees above its roof.
 // Bound its 5x5x5 clearance volume, five canopy overhang cells, and a 16-block trunk per footprint cell.
 private static final int MAX_MARKET_TREE_HEIGHT=16;
 private static final int MARKET_CLEARANCE_TOP=4;
 private static final int MAX_MARKET_SITE_OBSTACLES=5*5*(MARKET_CLEARANCE_TOP+1+MAX_MARKET_TREE_HEIGHT)+5;
 private static final int MAX_MARKET_SITE_RELIEF=3;
 private static final int MAX_MARKET_PREPARATION_FALLBACK_CANDIDATES=64;
 private static final int MARKET_PREPARATION_LOCAL_CANDIDATES=40;
 private static final int MARKET_PREPARATION_OUTER_CANDIDATES_PER_QUADRANT=6;
 private static final int MARKET_PREPARATION_LOCAL_RADIUS=42;
 private static final int MARKET_PREPARATION_LOCAL_STEP=3;
 private static final int MARKET_SITE_GRID_SAMPLES=13;
 private static final int MARKET_SITE_FALLBACK_CELLS=MARKET_SITE_GRID_SAMPLES-1;
 private static final int MAX_MARKET_SITE_FALLBACK_CANDIDATES=MARKET_SITE_FALLBACK_CELLS*MARKET_SITE_FALLBACK_CELLS;
 private static final int MARKET_SITE_SPOKE_SAMPLE_COUNT=4*4*3;
 private static final int MAX_MARKET_SITE_CANDIDATES=MARKET_SITE_SPOKE_SAMPLE_COUNT+MARKET_SITE_GRID_SAMPLES*MARKET_SITE_GRID_SAMPLES;
 private static final UUID SITE_LAYOUT_OWNER=new UUID(0L,0L);
 private static final Palette SITE_LAYOUT_PALETTE=new Palette(Blocks.OAK_PLANKS,Blocks.OAK_PLANKS,Blocks.OAK_LOG,
  Blocks.COBBLESTONE,Blocks.OAK_PLANKS,Blocks.OAK_STAIRS,Blocks.OAK_SLAB,Blocks.GLASS_PANE,
  Blocks.OAK_PLANKS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
 static final long PLAN_RETRY_TICKS=200L;
 static final long PLAN_INTERVAL_TICKS=2400L;
 public static boolean open(ServerLevel level) { long time=Math.floorMod(level.getOverworldClockTime(),24000L); return time>=de.villagerplus.Config.WORK_A_END && time<6000; }
 public static void register() {
  MarketDisplays.register();
  net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DEATH.register((entity,damage)->{
   if(entity instanceof Villager v && v.level() instanceof ServerLevel level) {
    var ref=Villages.find(level,v);if(ref==null)return;var s=shop(ref,v.getUUID());if(s!=null)updateShop(ref,s.died());
    ref.data().removeMarketDemands(v.getUUID());
   }
  });
 }

 public static MarketData of(VillageRef ref) { MarketData m=ref.bellEntity().getAttached(VillagerPlus.MARKET); return m==null?MarketData.empty():m; }
 public static void save(VillageRef ref,MarketData m) { ref.bellEntity().setAttached(VillagerPlus.MARKET,m); ref.dirty(); }
 public static MarketData.Shop shop(VillageRef ref,UUID owner) { return of(ref).shops().stream().filter(s->s.owner().equals(owner)).findFirst().orElse(null); }
 public static String role(VillageRef ref,Villager v) { var s=shop(ref,v.getUUID()); return s==null?"":(v.isBaby()?"Ladenlehrling": "Ladeninhaber")+" · "+KINDS[Math.floorMod(s.kind(),3)]; }
 public static boolean reserved(ServerLevel level,BlockPos pos,int half) {
  VillageRef ref=Villages.villageNear(level,pos,96);
  return reserved(level,ref,pos,half);
 }
 /** Checks a plaza/site candidate against the explicitly resolved village without another bell lookup. */
 public static boolean reserved(ServerLevel level,VillageRef ref,BlockPos pos,int half) {
  if(level==null||ref==null||pos==null) return false;
  int margin=Math.max(0,half);
  if(Plaza.inside(level,ref,pos,margin+5)) return true;
  return of(ref).shops().stream().anyMatch(s->Math.abs((long)s.origin().getX()-pos.getX())<=margin+9L
   &&Math.abs((long)s.origin().getZ()-pos.getZ())<=margin+9L);
 }
 static int targetShopCount(int residents) {
  if(residents<FIRST_STALL_POPULATION) return 0;
  if(residents<SECOND_STALL_POPULATION) return 1;
  if(residents<THIRD_STALL_POPULATION) return 2;
  return 3;
 }
 public static void plan(ServerLevel level,VillageRef ref,long now) {
  MarketData m=of(ref); if(now<m.nextPlan()) return;
  List<Villager> people=Villages.residents(level,ref,de.villagerplus.Config.VILLAGE_RADIUS,v->!Guests.isGuest(v));
  boolean deceased=m.shops().stream().anyMatch(MarketData.Shop::deceased);
  if(!deceased && m.shops().isEmpty() && people.size()<FIRST_STALL_POPULATION) {
   deferPlan(ref,m,now,PLAN_RETRY_TICKS); return;
  }
  int target=targetShopCount(people.size());
  if(!deceased && m.shops().size()>=target) { deferPlan(ref,m,now,PLAN_INTERVAL_TICKS); return; }

  int professionReassignmentPopulation=professionReassignmentPopulation(m,deceased);
  List<Villager> candidates=ownerCandidates(level,ref,people,people.size(),!deceased,deceased,
   professionReassignmentPopulation);
  if(candidates.isEmpty()) { deferPlan(ref,m,now,PLAN_RETRY_TICKS); return; }
  if(deceased) {
   for(int i=0;i<m.shops().size();i++) if(m.shops().get(i).deceased()) {
    var old=m.shops().get(i); Villager owner=candidates.getFirst();
    var heir=new MarketData.Shop(owner.getUUID(),old.origin(),old.facing(),old.kind(),old.progress(),old.opened(),now,old.palette(),false,0,old.sitePrepared());
    List<MarketData.Shop> replacement=new ArrayList<>(m.shops());replacement.set(i,heir);
    var be=level.getBlockEntity(stockPos(old));if(be!=null)be.setAttached(VillagerPlus.OWNER,owner.getUUID());
    save(ref,new MarketData(m.plaza(),List.copyOf(replacement),now+PLAN_INTERVAL_TICKS,m.sales()));return;
   }
  }

  // A missing plaza must not permanently disable trade. Use the known village footprint
  // midpoint as a temporary search anchor; candidate checks still reject protected or
  // occupied sites, and the search remains bounded to already loaded chunks.
  BlockPos anchor=m.plaza().orElseGet(()->Development.center(level,ref));
  SitePlan sitePlan=findSitePlan(level,ref,anchor,m.shops(),Math.floorDiv(now,PLAN_RETRY_TICKS));
  if(sitePlan==null) { deferPlan(ref,m,now,PLAN_RETRY_TICKS); return; }
  BlockPos site=sitePlan.pos();
  for(Villager owner:candidates) {
   // Rent is charged only after a real eligible footprint is found, from this owner's private wallet.
   if(!payRent(level,ref,owner)) continue;
   Palette p=Palette.detect(level,ref.bell(),owner,site.asLong(),site);
   var entry=new MarketData.Shop(owner.getUUID(),site,sitePlan.facing().get2DDataValue(),m.shops().size()%3,0,0,now,p.ids(),false,0);
   List<MarketData.Shop> shops=new ArrayList<>(m.shops());shops.add(entry);
   save(ref,new MarketData(m.plaza(),List.copyOf(shops),now+PLAN_INTERVAL_TICKS,m.sales()));
   org.slf4j.LoggerFactory.getLogger("villagerplus").info("Laden geplant: {} bei {}, Inhaber {}",KINDS[entry.kind()],site,owner.getUUID()); return;
  }
  deferPlan(ref,m,now,PLAN_RETRY_TICKS);
 }
 private static void deferPlan(VillageRef ref,MarketData market,long now,long delay) {
  long next=now+delay;
  if(market.nextPlan()!=next) save(ref,new MarketData(market.plaza(),market.shops(),next,market.sales()));
 }
 static int professionReassignmentPopulation(MarketData market,boolean deceased) {
  return market!=null&&!deceased&&market.shops().isEmpty()
   ?FIRST_STALL_POPULATION:OWNER_REASSIGNMENT_POPULATION;
 }
 static List<Villager> ownerCandidates(ServerLevel level,VillageRef ref,List<Villager> people,int population,
                                       boolean requireRent,boolean allowApprentice,
                                       int professionReassignmentPopulation) {
  return people.stream().filter(v->shop(ref,v.getUUID())==null
    &&eligibleOwner(v,population,allowApprentice,professionReassignmentPopulation))
   .filter(v->!requireRent || count(wallet(level,v),Items.EMERALD)>=STALL_RENT)
   .sorted(Comparator.comparingInt(v->allowApprentice && v.isBaby()?0:isUnemployed(v)?1:2)).toList();
 }
 static boolean eligibleOwner(Villager villager,int population,boolean allowApprentice,
                              int professionReassignmentPopulation) {
  if(!villager.isAlive() || villager.isBaby()&&!allowApprentice || Expedition.isAway(villager)) return false;
  var profession=villager.getVillagerData().profession();
  var key=profession.unwrapKey().orElse(null);
  if(key==null || !key.identifier().getNamespace().equals("minecraft")) return false;
  String job=String.valueOf(de.villagerplus.Compat.jobKey(profession));
  if(java.util.Set.of("farmer","mason","fletcher","nitwit").contains(job)) return false;
  return villager.isBaby() || key.identifier().getPath().equals("none")
   || population>=professionReassignmentPopulation;
 }
 private static boolean isUnemployed(Villager villager) {
  return villager.getVillagerData().profession().unwrapKey().map(k->k.identifier().getPath().equals("none")).orElse(false);
 }
 private record SitePlan(BlockPos pos,Direction facing) { }
 static BlockPos findSite(ServerLevel level,VillageRef ref,BlockPos center,List<MarketData.Shop> shops) {
  SitePlan plan=findSitePlan(level,ref,center,shops);
  return plan==null?null:plan.pos();
 }
 private static SitePlan findSitePlan(ServerLevel level,VillageRef ref,BlockPos center,List<MarketData.Shop> shops) {
  return findSitePlan(level,ref,center,shops,0L);
 }
 private static SitePlan findSitePlan(ServerLevel level,VillageRef ref,BlockPos center,List<MarketData.Shop> shops,long retryPhase) {
  List<BlockPos> regular=marketSiteSearchCandidates(ref,center);
  SitePlan direct=findSitePlan(level,ref,center,shops,regular);
  if(direct!=null) return direct;
  // The regular spokes/grid can miss a safe natural clearing between samples. Try the
  // bounded cell centers only after those established sites fail; each still goes through
  // the same natural-terrain, protection, entity, chunk, and shop-spacing checks.
  List<BlockPos> interstitial=marketSiteFallbackCandidates(ref,center);
  SitePlan between=findSitePlan(level,ref,center,shops,interstitial);
  if(between!=null) return between;

  // If no ready-or-ordinary-clearable site exists, let the owner reserve a modest natural
  // terrace and have normal market work cut it down in paid, inventory-backed steps.
  List<BlockPos> allCandidates=preparableMarketSiteCandidates(ref,center,regular,interstitial,retryPhase);
  BlockPos prepared=findPreparableMarketSite(level,ref,center,shops,allCandidates);
  return prepared==null?null:new SitePlan(prepared,marketFacingToward(center,prepared));
 }
 private static SitePlan findSitePlan(ServerLevel level,VillageRef ref,BlockPos center,List<MarketData.Shop> shops,List<BlockPos> candidates) {
  for(BlockPos candidate:candidates) {
   // The finished stall is a ground-level footprint: vertical separation never makes two
   // stalls safe to overlap. Filter known reservations before the more expensive terrain scan.
   if(!level.hasChunkAt(candidate)||tooCloseToExistingShop(shops,candidate)) continue;
   Direction facing=marketFacingToward(center,candidate);
   BlockPos preparedSite=prepareMarketSite(level,ref,candidate,facing);
   if(preparedSite!=null&&!tooCloseToExistingShop(shops,preparedSite)) return new SitePlan(preparedSite,facing);
  }
  return null;
 }

 /** Deterministic, retry-rotating candidate batch for the expensive natural-terrace preparation pass. */
 static List<BlockPos> preparableMarketSiteCandidates(VillageRef ref,BlockPos center) {
  return preparableMarketSiteCandidates(ref,center,0L);
 }
 static List<BlockPos> preparableMarketSiteCandidates(VillageRef ref,BlockPos center,long retryPhase) {
  if(ref==null||center==null) return List.of();
  List<BlockPos> regular=marketSiteSearchCandidates(ref,center);
  List<BlockPos> interstitial=marketSiteFallbackCandidates(ref,center);
  return preparableMarketSiteCandidates(ref,center,regular,interstitial,retryPhase);
 }

 private static List<BlockPos> preparableMarketSiteCandidates(VillageRef ref,BlockPos center,
   List<BlockPos> regular,List<BlockPos> interstitial,long retryPhase) {
  if(ref==null||center==null||regular==null||interstitial==null) return List.of();
  Set<BlockPos> unique=new HashSet<>();
  List<BlockPos> pool=new ArrayList<>(regular.size()+interstitial.size());
  for(BlockPos candidate:marketSiteSpokeCandidates(ref,center))
   if(unique.add(candidate.immutable())) pool.add(candidate.immutable());
  for(BlockPos candidate:regular)
   if(unique.add(candidate.immutable())) pool.add(candidate.immutable());
  for(BlockPos candidate:interstitial)
   if(unique.add(candidate.immutable())) pool.add(candidate.immutable());
  // The broad regular grid is intentionally coarse. Add a dense local lattice to the
  // expensive preparation pass so a compact natural clearing between those samples can
  // still be found without increasing the per-attempt terrain-check budget.
  for(int dx=-MARKET_PREPARATION_LOCAL_RADIUS;dx<=MARKET_PREPARATION_LOCAL_RADIUS;dx+=MARKET_PREPARATION_LOCAL_STEP)
   for(int dz=-MARKET_PREPARATION_LOCAL_RADIUS;dz<=MARKET_PREPARATION_LOCAL_RADIUS;dz+=MARKET_PREPARATION_LOCAL_STEP) {
    if((long)dx*dx+(long)dz*dz>(long)MARKET_PREPARATION_LOCAL_RADIUS*MARKET_PREPARATION_LOCAL_RADIUS) continue;
    BlockPos raw=center.offset(dx,0,dz);
    BlockPos candidate=clearMarketSearchRoute(center,ref,raw);
    if(candidate!=null&&unique.add(candidate.immutable())) pool.add(candidate.immutable());
   }
  if(pool.size()<=MAX_MARKET_PREPARATION_FALLBACK_CANDIDATES) {
   pool.sort(Comparator.comparingLong((BlockPos pos)->horizontalDistanceSquared(center,pos))
    .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
   return List.copyOf(pool);
  }

  // Keep guaranteed outer-area coverage, then spend the rest of the bounded checks on a
  // dense local window. The old 8x8 sampling spread all 64 checks over roughly 23-block
  // cells, so a 5x5 stand-sized clearing could sit between every checked point.
  long limit=de.villagerplus.Config.VILLAGE_RADIUS-4L;
  long outerRadiusSquared=limit*limit/4L;
  List<BlockPos> candidates=new ArrayList<>(MAX_MARKET_PREPARATION_FALLBACK_CANDIDATES);
  Set<BlockPos> selected=new HashSet<>(MAX_MARKET_PREPARATION_FALLBACK_CANDIDATES);
  int outerPerQuadrant=Math.min(MARKET_PREPARATION_OUTER_CANDIDATES_PER_QUADRANT,
   (MAX_MARKET_PREPARATION_FALLBACK_CANDIDATES-MARKET_PREPARATION_LOCAL_CANDIDATES)/4);
  // Preserve exploration in all four distant quadrants. Rotate each quadrant's window on
  // retries so an unloaded edge, road, or protected plot cannot permanently hide its peers.
  for(int quadrant=0;quadrant<4;quadrant++) {
   final int selectedQuadrant=quadrant;
   List<BlockPos> outer=pool.stream().filter(candidate-> {
    long dx=(long)candidate.getX()-ref.bell().getX(),dz=(long)candidate.getZ()-ref.bell().getZ();
    int candidateQuadrant=(dx<0?1:0)|(dz<0?2:0);
    return dx*dx+dz*dz>=outerRadiusSquared&&candidateQuadrant==selectedQuadrant;
   }).sorted(Comparator.comparingLong((BlockPos pos)->horizontalDistanceSquared(center,pos))
    .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ)).toList();
   if(outer.isEmpty()) continue;
   int start=(int)Math.floorMod(retryPhase*(long)Math.max(1,outerPerQuadrant),outer.size());
   int added=0;
   for(int offset=0;offset<outer.size()&&added<outerPerQuadrant;offset++) {
    BlockPos candidate=outer.get((start+offset)%outer.size());
    if(selected.add(candidate)) { candidates.add(candidate);added++; }
   }
  }

  List<BlockPos> local=new ArrayList<>();
  for(BlockPos candidate:pool) {
   if(horizontalDistanceSquared(center,candidate)<=(long)MARKET_PREPARATION_LOCAL_RADIUS*MARKET_PREPARATION_LOCAL_RADIUS)
    local.add(candidate);
  }
  local.sort(Comparator.comparingLong((BlockPos pos)->horizontalDistanceSquared(center,pos))
   .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
  int localWindows=Math.max(1,(local.size()+MARKET_PREPARATION_LOCAL_CANDIDATES-1)/MARKET_PREPARATION_LOCAL_CANDIDATES);
  int localStart=(int)Math.floorMod(retryPhase,(long)localWindows)*MARKET_PREPARATION_LOCAL_CANDIDATES;
  int localAdded=0;
  for(int offset=0;offset<local.size()&&localAdded<MARKET_PREPARATION_LOCAL_CANDIDATES;offset++) {
   BlockPos candidate=local.get((localStart+offset)%local.size());
   if(selected.add(candidate)) { candidates.add(candidate);localAdded++; }
  }

  // If route clipping or a village edge left fewer local sites, use the remaining checks
  // for the best-spaced candidates from the broad fallback pool.
  while(candidates.size()<MAX_MARKET_PREPARATION_FALLBACK_CANDIDATES&&selected.size()<pool.size()) {
   BlockPos best=null;
   long bestSpacing=-1;
   for(BlockPos candidate:pool) {
    if(selected.contains(candidate)) continue;
    long spacing=Long.MAX_VALUE;
    for(BlockPos chosen:candidates) spacing=Math.min(spacing,horizontalDistanceSquared(candidate,chosen));
    if(best==null||spacing>bestSpacing||spacing==bestSpacing
      &&(candidate.getX()<best.getX()||candidate.getX()==best.getX()&&candidate.getZ()<best.getZ())) {
     best=candidate;bestSpacing=spacing;
    }
   }
   if(best==null) break;
   selected.add(best);candidates.add(best);
  }
  candidates.sort(Comparator.comparingLong((BlockPos pos)->horizontalDistanceSquared(center,pos))
   .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
  return List.copyOf(candidates);
 }

 /** A bounded last-resort pass for safe natural terraces that need incremental paid-floor preparation. */
 static BlockPos findPreparableMarketSite(ServerLevel level,VillageRef ref,BlockPos center,List<MarketData.Shop> shops,
   List<BlockPos> candidates) {
  if(level==null||ref==null||center==null||shops==null||candidates==null) return null;
  for(BlockPos candidate:candidates) {
   if(candidate==null||!level.hasChunkAt(candidate)||!withinVillageDevelopmentArea(ref,candidate)
    ||Plaza.overlapsApproach(center,candidate,4)||tooCloseToExistingShop(shops,candidate)) continue;
   Direction facing=marketFacingToward(center,candidate);
   BlockPos site=prepareMarketSiteForNaturalTerrace(level,ref,candidate,facing);
   if(site!=null&&!tooCloseToExistingShop(shops,site)) return site;
  }
  return null;
 }

 private static boolean tooCloseToExistingShop(List<MarketData.Shop> shops,BlockPos site) {
  if(shops==null||site==null) return true;
  return shops.stream().anyMatch(shop->shop!=null&&horizontalDistanceSquared(shop.origin(),site)<64L);
 }

 private static BlockPos prepareMarketSiteForNaturalTerrace(ServerLevel level,VillageRef ref,BlockPos candidate,
   Direction facing) {
  NaturalSitePreparation.Grade grade=NaturalSitePreparation.inspectPreparableMarketGrade(level,candidate,2);
  if(grade.status()!=NaturalSitePreparation.Status.READY
    ||grade.highSurfaceY()-grade.lowSurfaceY()<=MAX_MARKET_SITE_RELIEF
    ||grade.lowSurfaceY()<=level.getMinY()+1
    ||grade.highSurfaceY()>=level.getMaxY()-MARKET_CLEARANCE_TOP) return null;
  BlockPos site=new BlockPos(candidate.getX(),grade.lowSurfaceY(),candidate.getZ());
  List<BlockPos> targets=marketSiteTargets(site,facing);
  SiteBounds bounds=marketSiteBounds(targets);
  var scan=inspectMarketSite(level,site,targets);
  if(scan.status()!=NaturalSitePreparation.Status.READY&&scan.status()!=NaturalSitePreparation.Status.CLEARABLE) return null;
  if(protectedMarketSite(level,ref,site,bounds)||occupiedMarketTarget(level,targets)) return null;
  return site;
 }

 /** Find a replacement for a persisted, obstructed shop before any paid construction has started. */
 static MarketData.Shop alternativeUnbuiltShopSite(ServerLevel level,VillageRef ref,MarketData.Shop blocked,long now) {
  if(level==null||ref==null||blocked==null||blocked.deceased()||blocked.opened()>0||blocked.progress()>0) return null;
  MarketData market=of(ref);
  boolean stillCurrent=market.shops().stream().anyMatch(shop->shop.owner().equals(blocked.owner())
   &&shop.origin().equals(blocked.origin())&&shop.opened()==0&&shop.progress()<=0&&!shop.deceased());
  if(!stillCurrent) return null;

  // Keep the blocked origin in the spacing list so the replacement cannot overlap the half-cleared plot.
  // Without a persisted plaza, do not recenter from Development.center: that extent includes this
  // saved shop footprint and would move the search anchor as soon as the shop was planned.
  BlockPos anchor=market.plaza().orElse(blocked.origin());
  SitePlan replacement=findSitePlan(level,ref,anchor,market.shops(),Math.floorDiv(now,PLAN_RETRY_TICKS));
  if(replacement==null||horizontalDistanceSquared(replacement.pos(),blocked.origin())<64L) return null;
  return new MarketData.Shop(blocked.owner(),replacement.pos(),replacement.facing().get2DDataValue(),blocked.kind(),
   0,0,now,blocked.palette(),false,0);
 }

 /** Stable, capped candidates: preserve nearby spokes, then sample the full village footprint. */
 static List<BlockPos> marketSiteSearchCandidates(VillageRef ref,BlockPos center) {
  if(ref==null||center==null) return List.of();
  List<BlockPos> candidates=new ArrayList<>(MAX_MARKET_SITE_CANDIDATES);
  Set<BlockPos> inspected=new HashSet<>(MAX_MARKET_SITE_CANDIDATES);
  for(BlockPos candidate:marketSiteSpokeCandidates(ref,center)) {
   if(inspected.add(candidate)) candidates.add(candidate);
  }

  // Thirteen evenly spaced samples per axis cover the bell-centered development bounds
  // with a bounded 169-point grid. Sorting by distance from the plaza keeps nearer clearings first.
  long limit=de.villagerplus.Config.VILLAGE_RADIUS-4L;
  List<BlockPos> grid=new ArrayList<>(MARKET_SITE_GRID_SAMPLES*MARKET_SITE_GRID_SAMPLES);
  for(int x=0;x<MARKET_SITE_GRID_SAMPLES;x++) for(int z=0;z<MARKET_SITE_GRID_SAMPLES;z++) {
   int sampleX=sampleMarketCoordinate(ref.bell().getX(),limit,x);
   int sampleZ=sampleMarketCoordinate(ref.bell().getZ(),limit,z);
   BlockPos raw=new BlockPos(sampleX,center.getY(),sampleZ);
   BlockPos candidate=clearMarketSearchRoute(center,ref,raw);
   if(candidate!=null) grid.add(candidate);
  }
  grid.sort(Comparator.comparingLong((BlockPos pos)->horizontalDistanceSquared(center,pos))
   .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
  for(BlockPos candidate:grid) if(inspected.add(candidate.immutable())) candidates.add(candidate.immutable());
  return List.copyOf(candidates);
 }

 /** The established radial samples remain available to the bounded preparation pass. */
 private static List<BlockPos> marketSiteSpokeCandidates(VillageRef ref,BlockPos center) {
  if(ref==null||center==null) return List.of();
  List<BlockPos> candidates=new ArrayList<>(MARKET_SITE_SPOKE_SAMPLE_COUNT);
  Set<BlockPos> inspected=new HashSet<>(MARKET_SITE_SPOKE_SAMPLE_COUNT);
  int[] radii={13,21,29,37};
  int[] lateral={0,-10,10};
  for(int radius:radii) for(Direction face:Direction.Plane.HORIZONTAL) for(int side:lateral) {
   BlockPos raw=center.relative(face,radius).relative(face.getClockWise(),side);
   addMarketSiteCandidate(ref,center,raw,candidates,inspected);
  }
  return List.copyOf(candidates);
 }

 /** Bounded interstitial fallback sites, centered in the gaps between the regular grid samples. */
 static List<BlockPos> marketSiteFallbackCandidates(VillageRef ref,BlockPos center) {
  if(ref==null||center==null) return List.of();
  Set<BlockPos> regular=new HashSet<>(marketSiteSearchCandidates(ref,center));
  Set<BlockPos> inspected=new HashSet<>(MAX_MARKET_SITE_FALLBACK_CANDIDATES);
  List<BlockPos> candidates=new ArrayList<>(MAX_MARKET_SITE_FALLBACK_CANDIDATES);
  long limit=de.villagerplus.Config.VILLAGE_RADIUS-4L;
  for(int x=0;x<MARKET_SITE_FALLBACK_CELLS;x++) for(int z=0;z<MARKET_SITE_FALLBACK_CELLS;z++) {
   int left=sampleMarketCoordinate(ref.bell().getX(),limit,x);
   int right=sampleMarketCoordinate(ref.bell().getX(),limit,x+1);
   int north=sampleMarketCoordinate(ref.bell().getZ(),limit,z);
   int south=sampleMarketCoordinate(ref.bell().getZ(),limit,z+1);
   BlockPos raw=new BlockPos(left+(right-left)/2,center.getY(),north+(south-north)/2);
   BlockPos candidate=clearMarketSearchRoute(center,ref,raw);
   if(candidate!=null&&!regular.contains(candidate)&&inspected.add(candidate.immutable())) candidates.add(candidate.immutable());
  }
  candidates.sort(Comparator.comparingLong((BlockPos pos)->horizontalDistanceSquared(center,pos))
   .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getZ));
  return List.copyOf(candidates);
 }

 private static void addMarketSiteCandidate(VillageRef ref,BlockPos center,BlockPos raw,List<BlockPos> candidates,Set<BlockPos> inspected) {
  BlockPos candidate=clearMarketSearchRoute(center,ref,raw);
  if(candidate!=null&&inspected.add(candidate.immutable())) candidates.add(candidate.immutable());
 }

 private static BlockPos clearMarketSearchRoute(BlockPos center,VillageRef ref,BlockPos raw) {
  BlockPos candidate=Plaza.clearApproachRoutes(center,raw,4);
  return candidate==null||!withinVillageDevelopmentArea(ref,candidate)?null:candidate.immutable();
 }

 private static int sampleMarketCoordinate(int center,long limit,int index) {
  long span=2L*limit;
  return Math.toIntExact((long)center-limit+span*index/(MARKET_SITE_GRID_SAMPLES-1));
 }

 private static long horizontalDistanceSquared(BlockPos first,BlockPos second) {
  long dx=(long)first.getX()-second.getX(),dz=(long)first.getZ()-second.getZ();
  return dx*dx+dz*dz;
 }

 static Direction marketFacingToward(BlockPos plaza,BlockPos site) {
  int dx=site.getX()-plaza.getX(),dz=site.getZ()-plaza.getZ();
  Direction outward=Math.abs(dx)>=Math.abs(dz)?(dx<0?Direction.WEST:Direction.EAST):(dz<0?Direction.NORTH:Direction.SOUTH);
  return outward.getOpposite();
 }

 private static boolean withinVillageDevelopmentArea(VillageRef ref,BlockPos candidate) {
  if(ref==null||candidate==null) return false;
  long limit=de.villagerplus.Config.VILLAGE_RADIUS-4L;
  return Math.abs((long)candidate.getX()-ref.bell().getX())<=limit
   &&Math.abs((long)candidate.getZ()-ref.bell().getZ())<=limit;
 }

 private record SiteBounds(int minX,int maxX,int minZ,int maxZ) { }
 static List<BlockPos> marketSiteTargets(BlockPos origin,Direction facing) {
  // Start from the paid build steps, then include the full open stall volume and one block
  // above its canopy. Vegetation between posts is not itself a paid block, but would otherwise
  // survive inside the finished stand and never reach the real-drop/inventory safeguard.
  MarketData.Shop layout=new MarketData.Shop(SITE_LAYOUT_OWNER,origin.immutable(),facing.get2DDataValue(),0,0,0,0,List.of(),false,0);
  Set<BlockPos> targets=new LinkedHashSet<>();
  hut(layout,SITE_LAYOUT_PALETTE).stream().map(step->step.pos().immutable()).forEach(targets::add);
  for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) for(int y=0;y<=MARKET_CLEARANCE_TOP;y++)
   targets.add(at(layout,x,z,y).immutable());
  return List.copyOf(targets);
 }
 private static SiteBounds marketSiteBounds(List<BlockPos> targets) {
  int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
  for(BlockPos pos:targets) { minX=Math.min(minX,pos.getX());maxX=Math.max(maxX,pos.getX());minZ=Math.min(minZ,pos.getZ());maxZ=Math.max(maxZ,pos.getZ()); }
  return new SiteBounds(minX,maxX,minZ,maxZ);
 }

 static NaturalSitePreparation.Scan inspectMarketSite(ServerLevel level,BlockPos origin,List<BlockPos> targets) {
  BlockPos highestTarget=null;
  BlockPos highestTreeLog=null;
  Set<Long> checkedTreeColumns=new HashSet<>();
  int obstacles=0;
  for(BlockPos pos:targets) {
   if(!level.hasChunkAt(pos)) return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_CHUNK,null,obstacles);
   BlockState state=level.getBlockState(pos);
   if(state.hasBlockEntity()||level.getBlockEntity(pos)!=null||!state.getFluidState().isEmpty())
    return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,null,obstacles);
   if(state.isAir()) continue;
   if(pos.getY()<origin.getY()-1) {
    if(!safeMarketSupportTarget(level,pos,state)) return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,null,obstacles);
    continue;
   }
   // The paid 5x5 floor covers the low surface. Above that floor, only sturdy natural
   // terrain may be cut; use the highest remaining block first to avoid floating layers.
   if(pos.getY()>=origin.getY()&&safeMarketSupportTarget(level,pos,state)) {
    obstacles++;
    if(highestTarget==null||pos.getY()>highestTarget.getY()) highestTarget=pos.immutable();
    if(obstacles>MAX_MARKET_SITE_OBSTACLES) return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,null,obstacles);
    continue;
   }
   var cell=NaturalSitePreparation.inspectMarketTarget(level,pos,pos.getY()==origin.getY()-1);
   NaturalSitePreparation.Scan treeClearance=null;
   if(state.is(BlockTags.OVERWORLD_NATURAL_LOGS)
    &&checkedTreeColumns.add(new BlockPos(pos.getX(),0,pos.getZ()).asLong())) {
    treeClearance=inspectMarketTreeClearance(level,origin,pos);
    if(treeClearance.status()!=NaturalSitePreparation.Status.CLEARABLE)
     return new NaturalSitePreparation.Scan(treeClearance.status(),treeClearance.next(),obstacles);
    obstacles+=treeClearance.obstacleCount();
    if(obstacles>MAX_MARKET_SITE_OBSTACLES)
     return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,null,obstacles);
   }
   if(cell.status()==NaturalSitePreparation.Status.CLEARABLE) {
    obstacles+=cell.obstacleCount();
    if(state.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
     BlockPos treeTarget=treeClearance==null||treeClearance.next()==null?cell.next():treeClearance.next();
     if(highestTreeLog==null||treeTarget.getY()>highestTreeLog.getY()) highestTreeLog=treeTarget;
    } else if(highestTarget==null||cell.next().getY()>highestTarget.getY()) highestTarget=cell.next();
   } else if(cell.status()!=NaturalSitePreparation.Status.READY) {
    return new NaturalSitePreparation.Scan(cell.status(),cell.next(),obstacles+cell.obstacleCount());
   }
   if(obstacles>MAX_MARKET_SITE_OBSTACLES) return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,null,obstacles);
  }
  // Fell rooted trunks from their top down so each remaining log can still be verified against its soil and canopy.
  if(highestTreeLog!=null) return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.CLEARABLE,highestTreeLog,obstacles);
  return highestTarget==null
   ? new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.READY,null,0)
   : new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.CLEARABLE,highestTarget,obstacles);
 }

 /** Find the top of a verified natural trunk, but bound how high market preparation may fell it. */
 private static NaturalSitePreparation.Scan inspectMarketTreeClearance(ServerLevel level,BlockPos origin,BlockPos log) {
  BlockState state=level.getBlockState(log);
  if(!state.hasProperty(RotatedPillarBlock.AXIS)||state.getValue(RotatedPillarBlock.AXIS)!=Direction.Axis.Y)
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,log,0);
  BlockPos cursor=log;
  int treeHeightLimit=Math.min(origin.getY()+MAX_MARKET_TREE_HEIGHT,level.getMaxY()-2);
  while(cursor.getY()<treeHeightLimit&&cursor.getY()<level.getMaxY()-1) {
   BlockPos above=cursor.above();
   if(!level.hasChunkAt(above))
    return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_CHUNK,above,0);
   BlockState aboveState=level.getBlockState(above);
   if(!aboveState.is(BlockTags.OVERWORLD_NATURAL_LOGS)
    ||!aboveState.hasProperty(RotatedPillarBlock.AXIS)
    ||aboveState.getValue(RotatedPillarBlock.AXIS)!=Direction.Axis.Y) {
    return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.CLEARABLE,cursor.immutable(),
     Math.max(0,cursor.getY()-(origin.getY()+MARKET_CLEARANCE_TOP)));
   }
   cursor=above;
  }
  BlockPos above=cursor.above();
  if(!level.hasChunkAt(above))
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_CHUNK,above,0);
  BlockState aboveState=level.getBlockState(above);
  if(aboveState.is(BlockTags.OVERWORLD_NATURAL_LOGS)
   &&aboveState.hasProperty(RotatedPillarBlock.AXIS)
   &&aboveState.getValue(RotatedPillarBlock.AXIS)==Direction.Axis.Y)
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,above,0);
  return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.CLEARABLE,cursor.immutable(),
   Math.max(0,cursor.getY()-(origin.getY()+MARKET_CLEARANCE_TOP)));
 }

 private static boolean safeMarketSupportTarget(ServerLevel level,BlockPos pos,BlockState state) {
  return state.isFaceSturdy(level,pos,Direction.UP)&&VillageStyle.natural(state)
   &&!state.is(BlockTags.LEAVES)&&!state.is(BlockTags.LOGS)&&!state.is(Blocks.BEDROCK)
   &&!state.is(Blocks.ICE)&&!state.is(Blocks.PACKED_ICE)&&!state.is(Blocks.BLUE_ICE);
 }

 private static boolean protectedMarketSite(ServerLevel level,VillageRef ref,BlockPos site,SiteBounds bounds) {
  if(level.getPoiManager().getInRange(poi->true,site,10,
   net.minecraft.world.entity.ai.village.poi.PoiManager.Occupancy.ANY)
   .anyMatch(poi->poi.getPos().getX()>=bounds.minX()-1&&poi.getPos().getX()<=bounds.maxX()+1
    &&poi.getPos().getZ()>=bounds.minZ()-1&&poi.getPos().getZ()<=bounds.maxZ()+1)) return true;
  if(ref==null) return true;
  for(ZonePolicy.Zone zone:ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES,List.of())) {
   if("work".equals(zone.kind())) continue;
   if(zone.maxX()>=bounds.minX()-1&&zone.minX()<=bounds.maxX()+1
    &&zone.maxZ()>=bounds.minZ()-1&&zone.minZ()<=bounds.maxZ()+1) return true;
  }
  // Keep planned stalls out of existing homes and other solid village structures, including
  // structures that have no nearby POI. This is a read-only guard; it never clears or replaces
  // a built block while preparing a public market plot.
  if(Sites.touchesBuilding(level,site,bounds.minX()-site.getX(),bounds.maxX()-site.getX(),
   bounds.minZ()-site.getZ(),bounds.maxZ()-site.getZ(),MARKET_CLEARANCE_TOP)) return true;
  if(overlapsActiveBuildProject(ref,bounds)) return true;
  // Gravel is valid natural ground to the grading API, but is also a common CTOV road surface.
  // Preserve any such surface under the planned stall, then check the mod's saved walk corridor.
  if(marketOverlapsGravelSurface(level,site,bounds.minX(),bounds.maxX(),bounds.minZ(),bounds.maxZ())) return true;
  return marketOverlapsSavedRoad(level,ref,site,bounds.minX(),bounds.maxX(),bounds.minZ(),bounds.maxZ());
 }

 private static boolean overlapsActiveBuildProject(VillageRef ref,SiteBounds bounds) {
  if(ref==null||bounds==null) return true;
  VillageData.PlanBounds marketBounds=new VillageData.PlanBounds(bounds.minX(),bounds.maxX(),bounds.minZ(),bounds.maxZ());
  for(VillageData.BuildProject project:ref.data().buildProjects) {
   if(!project.active()) continue;
   // Current projects persist their exact reservation. Older active records without bounds get
   // a conservative footprint until the builder reconstructs and saves their plan.
   VillageData.PlanBounds reserved=project.bounds().orElseGet(()->{
    int half=project.type().isEmpty()?3:Math.max(16,Blueprints.half(project.type())+3);
    BlockPos origin=project.origin();
    return new VillageData.PlanBounds(origin.getX()-half,origin.getX()+half,origin.getZ()-half,origin.getZ()+half);
   });
   if(marketBounds.overlaps(reserved)) return true;
  }
  return false;
 }

 static boolean marketOverlapsSavedRoad(ServerLevel level,VillageRef ref,BlockPos site,int minX,int maxX,int minZ,int maxZ) {
  if(level==null||ref==null||site==null||minX>maxX||minZ>maxZ) return false;
  for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++)
   if(Roads.trustedVillagePathCell(level,ref,new BlockPos(x,site.getY()-1,z))) return true;
  return false;
 }

 /** Gravel can be either natural ground or a generated road; conservatively keep both intact under a market. */
 static boolean marketOverlapsGravelSurface(ServerLevel level,BlockPos site,int minX,int maxX,int minZ,int maxZ) {
  if(level==null||site==null||minX>maxX||minZ>maxZ) return true;
  for(int x=minX;x<=maxX;x++) for(int z=minZ;z<=maxZ;z++) {
   BlockPos column=new BlockPos(x,site.getY(),z);
   if(!level.hasChunkAt(column)) return true;
   int surfaceY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,x,z);
   BlockPos ground=new BlockPos(x,surfaceY-1,z);
   if(!level.hasChunkAt(ground)||level.getBlockState(ground).is(Blocks.GRAVEL)) return true;
  }
  return false;
 }

 static AABB marketSiteTargetVolume(List<BlockPos> targets) {
  if(targets==null||targets.isEmpty()) return null;
  int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
  int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
  for(BlockPos pos:targets) {
   minX=Math.min(minX,pos.getX());maxX=Math.max(maxX,pos.getX());
   minY=Math.min(minY,pos.getY());maxY=Math.max(maxY,pos.getY());
   minZ=Math.min(minZ,pos.getZ());maxZ=Math.max(maxZ,pos.getZ());
  }
  return new AABB(minX,minY,minZ,maxX+1.0,maxY+1.0,maxZ+1.0);
 }

 static boolean marketEntityOverlapsTargets(LivingEntity entity,List<BlockPos> targets) {
  return entity!=null&&targets!=null&&!targets.isEmpty()
   &&targets.stream().anyMatch(pos->entity.getBoundingBox().intersects(new AABB(pos)));
 }

 static boolean occupiedMarketTarget(ServerLevel level,List<BlockPos> targets) {
  if(targets==null||targets.isEmpty()) return true;
  AABB volume=marketSiteTargetVolume(targets);
  return !level.getEntitiesOfClass(LivingEntity.class,volume,
   entity->entity.isAlive()&&marketEntityOverlapsTargets(entity,targets)).isEmpty();
 }

 static BlockPos prepareMarketSite(ServerLevel level,VillageRef ref,BlockPos candidate,Direction facing) {
  // Search candidates inherit the plaza/development anchor's Y. Rebase each one to its own
  // loaded natural surface before grading so an elevated bell or a valley-side plaza cannot
  // reject every otherwise safe site just because the local terrain is several blocks away.
  BlockPos surfaceCenter=marketSurfaceAnchor(level,candidate);
  if(surfaceCenter==null) return null;
  var grade=NaturalSitePreparation.inspectTerracedMarketGrade(level,surfaceCenter,2,MAX_MARKET_SITE_RELIEF);
  if(grade.status()!=NaturalSitePreparation.Status.READY) return null;
  // A single step still uses the paid support layer. For larger relief, anchor at the
  // low surface and cut the high side down to the same paid 5x5 floor plane.
  int siteY=grade.highSurfaceY()-grade.lowSurfaceY()<=1?grade.highSurfaceY():grade.lowSurfaceY();
  BlockPos site=new BlockPos(candidate.getX(),siteY,candidate.getZ());
  List<BlockPos> targets=marketSiteTargets(site,facing);
  SiteBounds bounds=marketSiteBounds(targets);
  var scan=inspectMarketSite(level,site,targets);
  if(scan.status()!=NaturalSitePreparation.Status.READY&&scan.status()!=NaturalSitePreparation.Status.CLEARABLE) return null;

  // The paid 5x5 platform remains level; only bounded natural ground above its low-side plane is cut.
  if(protectedMarketSite(level,ref,site,bounds)) return null;
  if(occupiedMarketTarget(level,targets)) return null;
  return site;
 }

 private static BlockPos marketSurfaceAnchor(ServerLevel level,BlockPos candidate) {
  if(level==null||candidate==null||!level.hasChunkAt(candidate)) return null;
  int surfaceY=level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
   candidate.getX(),candidate.getZ());
  if(surfaceY<=level.getMinY()+1||surfaceY>=level.getMaxY()-MARKET_CLEARANCE_TOP) return null;
  return new BlockPos(candidate.getX(),surfaceY,candidate.getZ());
 }

 static NaturalSitePreparation.Scan clearMarketSiteStep(ServerLevel level,VillageRef ref,MarketData.Shop shop,Villager worker) {
  List<BlockPos> targets=marketSiteTargets(shop.origin(),facing(shop));
  if(protectedMarketSite(level,ref,shop.origin(),marketSiteBounds(targets)))
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,null,0);
  var scan=inspectMarketSite(level,shop.origin(),targets);
  if(scan.status()!=NaturalSitePreparation.Status.CLEARABLE) return scan;
  var cleared=clearMarketSiteTarget(level,ref,shop.origin(),scan.next(),worker);
  return new NaturalSitePreparation.Scan(cleared.status(),cleared.next(),scan.obstacleCount());
 }

 private static NaturalSitePreparation.Scan clearMarketSiteTarget(ServerLevel level,VillageRef ref,BlockPos origin,BlockPos pos,Villager worker) {
  if(level==null||origin==null||pos==null||!level.hasChunkAt(pos))
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_CHUNK,pos,0);
  BlockState state=level.getBlockState(pos);
  if(state.hasBlockEntity()||level.getBlockEntity(pos)!=null||!state.getFluidState().isEmpty())
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,pos,0);
  boolean terraceCut=pos.getY()>=origin.getY()&&safeMarketSupportTarget(level,pos,state);
  var eligibility=terraceCut
   ?new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.CLEARABLE,pos,1)
   :NaturalSitePreparation.inspectMarketTarget(level,pos,pos.getY()==origin.getY()-1);
  if(eligibility.status()!=NaturalSitePreparation.Status.CLEARABLE) return eligibility;
  if(!level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(pos),entity->entity.isAlive()).isEmpty())
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_ENTITY,pos,eligibility.obstacleCount());
	if(worker==null) return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_INVENTORY,pos,eligibility.obstacleCount());
	List<Container> publicStorage=ref==null?List.of():Balance.storage(level,ref);
	MarketClearingTool clearingTool=marketClearingTool(worker.getInventory(),publicStorage,state);
	ItemStack tool=clearingTool.stack();
	// Pickaxe-mined natural terrain drops nothing without a real pickaxe. Wait for a carried
	// or public tool instead of silently deleting stone while preparing the market floor.
	if(state.is(BlockTags.MINEABLE_WITH_PICKAXE)&&tool.isEmpty())
	 return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_TOOL,pos,eligibility.obstacleCount());
	if(!hasCorrectMarketDropTool(state,tool))
	 return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_TOOL,pos,eligibility.obstacleCount());
	List<ItemStack> drops=Block.getDrops(state,level,pos,null,worker,tool.copy());
	List<Container> destinations=marketDropDestinations(worker,publicStorage);
	if(!canFitAllMarketDrops(destinations,drops))
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.WAITING_FOR_INVENTORY,pos,eligibility.obstacleCount());
  if(!level.getBlockState(pos).equals(state)||!level.setBlock(pos,Blocks.AIR.defaultBlockState(),3))
   return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.BLOCKED,pos,eligibility.obstacleCount());
	Anim.blockBreak(level,pos,state);
	if(!tool.isEmpty()&&tool.isDamageableItem()) {
	 tool.hurtAndBreak(1,level,null,ignored->{ });
	 if(clearingTool.container()!=null) clearingTool.container().setChanged();
	}
	for(ItemStack drop:drops) if(!drop.isEmpty()) storeMarketDrop(worker,publicStorage,drop.copy());
	worker.getInventory().setChanged();
  return new NaturalSitePreparation.Scan(NaturalSitePreparation.Status.CLEARED,pos.immutable(),eligibility.obstacleCount());
 }

 private record MarketClearingTool(Container container,ItemStack stack) { }

 static boolean hasCorrectMarketDropTool(BlockState state,ItemStack tool) {
  return state!=null&&(!state.requiresCorrectToolForDrops()
   ||tool!=null&&!tool.isEmpty()&&tool.isCorrectToolForDrops(state));
 }

 private static MarketClearingTool marketClearingTool(Container inventory,List<Container> publicStorage,BlockState state) {
  if(state==null) return new MarketClearingTool(null,ItemStack.EMPTY);
  java.util.function.Predicate<ItemStack> preferredTool;
  if(state.is(BlockTags.LEAVES)) preferredTool=stack->stack.is(Items.SHEARS);
  else if(state.is(BlockTags.OVERWORLD_NATURAL_LOGS)||state.is(BlockTags.MINEABLE_WITH_AXE))
   preferredTool=stack->stack.is(ItemTags.AXES);
  else if(state.is(BlockTags.MINEABLE_WITH_PICKAXE)) preferredTool=stack->stack.is(ItemTags.PICKAXES);
  else if(state.is(BlockTags.MINEABLE_WITH_SHOVEL)) preferredTool=stack->stack.is(ItemTags.SHOVELS);
  else return new MarketClearingTool(null,ItemStack.EMPTY);
  if(inventory!=null) for(int slot=0;slot<inventory.getContainerSize();slot++) {
   ItemStack stack=inventory.getItem(slot);
   if(!stack.isEmpty()&&preferredTool.test(stack)) return new MarketClearingTool(inventory,stack);
  }
  // Borrow only from registered public village stock. Private chests never enter this list.
  if(publicStorage!=null) for(Container store:publicStorage) {
   if(store==null) continue;
   for(int slot=0;slot<store.getContainerSize();slot++) {
    ItemStack stack=store.getItem(slot);
    if(!stack.isEmpty()&&preferredTool.test(stack)) return new MarketClearingTool(store,stack);
   }
  }
  // No tool is synthesized; blocks with hand drops can still be cleared normally.
  return new MarketClearingTool(null,ItemStack.EMPTY);
 }

 private static List<Container> marketDropDestinations(Villager worker,List<Container> publicStorage) {
  List<Container> destinations=new ArrayList<>();
  if(worker!=null) destinations.add(worker.getInventory());
  if(publicStorage!=null) for(Container store:publicStorage)
   if(store!=null&&(worker==null||store!=worker.getInventory())) destinations.add(store);
  return destinations;
 }

 static boolean canFitAllMarketDrops(List<Container> destinations,List<ItemStack> drops) {
  List<SimpleContainer> simulated=new ArrayList<>();
  if(destinations!=null) for(Container destination:destinations) {
   if(destination==null) continue;
   SimpleContainer copy=new SimpleContainer(destination.getContainerSize());
   for(int slot=0;slot<destination.getContainerSize();slot++) copy.setItem(slot,destination.getItem(slot).copy());
   simulated.add(copy);
  }
  for(ItemStack drop:drops) if(!drop.isEmpty()) {
   ItemStack remaining=drop.copy();
   for(SimpleContainer destination:simulated) {
    if(remaining.isEmpty()) break;
    remaining=destination.addItem(remaining);
   }
   if(!remaining.isEmpty()) return false;
  }
  return true;
 }

 private static void storeMarketDrop(Villager worker,List<Container> publicStorage,ItemStack drop) {
  ItemStack remaining=worker.getInventory().addItem(drop);
  if(publicStorage!=null) for(Container store:publicStorage) {
   if(remaining.isEmpty()) break;
   if(store!=null) remaining=HopperBlockEntity.addItem(null,store,remaining,null);
  }
  if(!remaining.isEmpty()) throw new IllegalStateException("Market site drop storage changed after its capacity check");
 }
 private static Direction facing(MarketData.Shop s) { return Direction.from2DDataValue(s.facing()); }
 private static BlockPos local(BlockPos origin,Direction direction,int x,int y,int z) { return origin.relative(direction.getClockWise(),x).relative(direction,z).above(y); }
 static BlockPos at(MarketData.Shop s,int x,int z,int y) { return local(s.origin(),facing(s),x,y,z); }
 static BlockPos counter(MarketData.Shop s) { return at(s,0,3,0); }
 static BlockPos stockPos(MarketData.Shop s) { return at(s,-1,-1,0); }
 static void refreshDisplays(ServerLevel level,VillageRef ref,MarketData.Shop shop) {
  BlockPos pos=stockPos(shop);if(!level.hasChunkAt(pos))return;
  Container shelf=stock(level,shop);
  if(shelf==null)MarketDisplays.clear(level,ref.bell(),shop.origin());
  else MarketDisplays.sync(level,ref,shop,shelf);
 }
 public static List<Step> hut(MarketData.Shop s,Palette p) {
  Map<BlockPos,Step> plan=new LinkedHashMap<>();
  Block roof=p.roofSlab() instanceof SlabBlock?p.roofSlab():p.roofFull();
  Block timber=p.frame()==null?p.base():p.frame();
  // A 5x5 paid platform is substantially smaller than a house. The counter and
  // stocked chest sit inside the same compact footprint rather than behind house walls.
  for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++)
   plan.put(at(s,x,z,-2),Step.block(at(s,x,z,-2),p.base()));
  for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++)
   plan.put(at(s,x,z,-1),Step.block(at(s,x,z,-1),p.floor()));

  // Open timber posts make this read as a market canopy rather than a sixth house.
  for(int x:new int[]{-2,2}) for(int z:new int[]{-2,2}) for(int y=0;y<3;y++) {
   BlockPos pos=at(s,x,z,y);
   plan.put(pos,Step.block(pos,timber));
  }

  // A short backboard frames the merchandise; the three-block front counter stays open to buyers.
  for(int x=-1;x<=1;x++) {
   BlockPos lower=at(s,x,-2,0),upper=at(s,x,-2,1),header=at(s,x,-2,2);
   plan.put(lower,Step.block(lower,p.wall()));
   plan.put(upper,Step.block(upper,p.wall2()));
   plan.put(header,Step.block(header,timber));
   BlockPos counter=at(s,x,2,0);
   plan.put(counter,Step.block(counter,roof));
  }

  // The main canopy extends one block over the public counter. Its palette-coloured
  // front stripes vary by stall type without introducing untracked wool or free blocks.
  Block accent=switch(Math.floorMod(s.kind(),3)) {
   case 0 -> p.base();
   case 1 -> p.wall2();
   default -> timber;
  };
  for(int x=-2;x<=2;x++) for(int z=-2;z<=2;z++) {
   BlockPos pos=at(s,x,z,3);
   plan.put(pos,Step.block(pos,roof));
  }
  for(int x=-2;x<=2;x++) {
   BlockPos pos=at(s,x,3,3);
   plan.put(pos,Step.block(pos,Math.floorMod(x+Math.floorMod(s.kind(),3),2)==0?roof:accent));
  }

  BlockPos stock=stockPos(s);
  plan.put(stock,Step.block(stock,Blocks.CHEST));
  return new ArrayList<>(plan.values());
 }
 private static Palette palette(ServerLevel l,VillageRef r,MarketData.Shop s,Villager v) {
  Palette p=Palette.ofIds(s.palette()); return p==null?Palette.detect(l,r.bell(),v,s.origin().asLong(),s.origin()):p;
 }

 /** Move only identifiable pre-project shop wishes out of the legacy shared wanted list. */
 public static List<String> migrateLegacyWants(Ctx c,List<String> legacy) {
  if(c==null || c.village()==null || legacy==null || legacy.isEmpty()) return legacy==null?List.of():List.copyOf(legacy);
  VillageRef ref=c.village();List<String> remaining=new ArrayList<>(legacy);
  List<Container> stores=Balance.storage(c.level(),ref);
  for(MarketData.Shop shop:of(ref).shops()) {
   if(shop.deceased()) continue;
   List<Step> steps=hut(shop,palette(c.level(),ref,shop,c.v()));
   if(shop.progress()<0 || shop.progress()>=steps.size()) continue;
   Step next=steps.get(shop.progress());
   if(next.free() || next.item()==null || next.cost()<=0 || count(stores,next.item())>=next.cost()) continue;
   String item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(next.item()).toString();
   if(remaining.remove(item+"#32")) ref.data().addMarketDemand(Optional.of(shop.owner()),item,32);
  }
  return List.copyOf(remaining);
 }

 private static void updateShop(VillageRef ref,MarketData.Shop s) {
  MarketData m=of(ref);List<MarketData.Shop> shops=new ArrayList<>(m.shops());
  for(int i=0;i<shops.size();i++) if(shops.get(i).owner().equals(s.owner())) shops.set(i,s);
  save(ref,new MarketData(m.plaza(),List.copyOf(shops),m.nextPlan(),m.sales()));
 }
 static MarketData.Shop persistSitePrepared(VillageRef ref,MarketData.Shop planned,long now) {
  if(ref==null||planned==null||planned.deceased()||planned.opened()>0||planned.progress()>0) return null;
  MarketData.Shop persisted=of(ref).shops().stream().filter(shop->shop.owner().equals(planned.owner())
   &&shop.origin().equals(planned.origin())&&shop.opened()==0&&shop.progress()<=0&&!shop.deceased())
   .findFirst().orElse(null);
  if(persisted==null) return null;
  MarketData.Shop prepared=persisted.prepared(now);
  updateShop(ref,prepared);
  return prepared;
 }
 private static void retryMarketPreparation(Ctx c,MarketData.Shop shop,String activity) {
  c.setActivity(activity);
  updateShop(c.village(),shop.retry(c.now()+PLAN_RETRY_TICKS));
  c.st().cooldownUntil=c.now()+100;
 }
 public static boolean work(Ctx c) {
  if(c.village()==null) return false; MarketData.Shop s=shop(c.village(),c.v().getUUID()); if(s==null) return false;
  if(s.deceased() || c.now()<s.retryAt()) return false;
  Palette p=palette(c.level(),c.village(),s,c.v()); List<Step> steps=hut(s,p);
  boolean firstPaidStepAlreadyPlaced=s.progress()<0&&!steps.isEmpty()
   &&c.level().getBlockState(steps.getFirst().pos()).equals(
    steps.getFirst().make().apply(steps.getFirst().item()==null?Blocks.AIR:Block.byItem(steps.getFirst().item())));
  if(s.opened()==0&&s.progress()<=0&&!firstPaidStepAlreadyPlaced) {
   c.setActivity("bereitet den Marktplatzstandort vor");
   if(!JobUtil.approach(c,counter(s),2.5)) return true;
   var preparation=clearMarketSiteStep(c.level(),c.village(),s,c.v());
   if(preparation.status()==NaturalSitePreparation.Status.CLEARED) {
    c.setActivity("räumt den Marktplatzstandort"); Anim.hold(c,Items.STONE_HOE); JobUtil.swing(c.v());
    c.st().cooldownUntil=c.now()+20; return true;
   }
   if(preparation.status()==NaturalSitePreparation.Status.WAITING_FOR_INVENTORY) {
    retryMarketPreparation(c,s,"braucht freien Inventarplatz für die Rodung"); return true;
   }
   if(preparation.status()==NaturalSitePreparation.Status.WAITING_FOR_TOOL) {
    retryMarketPreparation(c,s,"wartet auf ein öffentliches Werkzeug für die Rodung"); return true;
   }
   if(preparation.status()==NaturalSitePreparation.Status.WAITING_FOR_CHUNK
    ||preparation.status()==NaturalSitePreparation.Status.WAITING_FOR_ENTITY) {
    retryMarketPreparation(c,s,"wartet auf einen freien Marktplatzstandort"); return true;
   }
   if(preparation.status()==NaturalSitePreparation.Status.BLOCKED) {
    MarketData.Shop alternative=alternativeUnbuiltShopSite(c.level(),c.village(),s,c.now());
    if(alternative!=null) {
     updateShop(c.village(),alternative);
     c.setActivity("sucht einen sicheren Ersatzstandort für den Markt");
     c.st().cooldownUntil=c.now()+20; return true;
    }
    c.setActivity("wartet auf einen sicheren Marktplatzstandort");
    updateShop(c.village(),s.retry(c.now()+PLAN_RETRY_TICKS));
    c.st().cooldownUntil=c.now()+100; return false;
   }
   // Persist preparation completion as its own durable phase before the first paid block.
   if(!s.sitePrepared()) {
    s=persistSitePrepared(c.village(),s,c.now());
    if(s==null) return false;
   }
   // Negative progress remains the replay-safe marker for the first paid block: after a
   // crash, a block placed just before progress advances is recognized without charging twice.
   s=s.progress(-1,c.now(),false); updateShop(c.village(),s);
  }
  if(s.opened()>0 && stock(c.level(),s)==null) {
   MarketDisplays.clear(c.level(),c.village().bell(),s.origin());
   if(!c.level().getBlockState(stockPos(s)).canBeReplaced()) { updateShop(c.village(),s.retry(c.now()+1200));return false; }
   for(int k=0;k<steps.size();k++) if(steps.get(k).pos().equals(stockPos(s))) {
    s=new MarketData.Shop(s.owner(),s.origin(),s.facing(),s.kind(),k,0,c.now(),s.palette(),false,0);updateShop(c.village(),s);break;
   }
  }
  if(s.progress()<steps.size()) {
   c.setActivity("baut den eigenen "+KINDS[s.kind()]);
   int progress=Math.max(0,s.progress());
   Step st=steps.get(progress);
   if(!JobUtil.approach(c,counter(s),2.5)) return true;
   var current=c.level().getBlockState(st.pos());
   var desired=st.make().apply(st.item()==null?Blocks.AIR:Block.byItem(st.item()));
   if(!current.equals(desired)) {
    if(!current.isAir() && !current.canBeReplaced() && !VillageStyle.natural(current)) {
     // A saved READY marker may resume directly at the first paid block after restart.
     // If that still-unbuilt cell became protected in the meantime, move the unopened
     // stall instead of leaving its owner retrying the same unsafe plot forever.
     if(s.opened()==0&&progress==0) {
      MarketData.Shop alternative=alternativeUnbuiltShopSite(c.level(),c.village(),s,c.now());
      if(alternative!=null) {
       updateShop(c.village(),alternative);
       c.setActivity("sucht einen sicheren Ersatzstandort für den Markt");
       c.st().cooldownUntil=c.now()+20; return true;
      }
     }
     c.st().cooldownUntil=c.now()+100; updateShop(c.village(),s.retry(c.now()+1200)); return false;
    }
    if(!c.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,new AABB(st.pos()),e->e.isAlive()).isEmpty()) return true;
    if(!st.free()) {
     List<Container> stores=Balance.storage(c.level(),c.village());
     if(count(stores,st.item())<st.cost()) {
      String wish=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.item()).toString();
      c.village().data().addMarketDemand(Optional.of(s.owner()),wish,32);
      c.village().dirty();
      c.setActivity("wartet auf Ladenmaterial"); c.st().cooldownUntil=c.now()+100; return true;
     }
     take(stores,st.item(),st.cost());
    }
    c.level().setBlock(st.pos(),desired,3); JobUtil.swing(c.v()); Anim.hold(c,Items.STONE_AXE);
   }
   int n=progress+1; updateShop(c.village(),s.progress(n,c.now(),n==steps.size()));
   c.village().data().removeMarketDemands(s.owner());
   c.mem().jobsDone++;
   if(n==steps.size()) {
    Journal.add(c.level(),c.village(),"Bauen",c.mem().name,KINDS[s.kind()]+" eröffnet",0);
    var be=c.level().getBlockEntity(stockPos(s));if(be!=null) be.setAttached(VillagerPlus.OWNER,c.v().getUUID());
    JobUtil.tellNearbyPlayers(c.level(),c.v(),32,Component.literal(c.mem().name+" eröffnet einen "+KINDS[s.kind()]+"."));
   }
   c.st().cooldownUntil=c.now()+20; return true;
  }
  c.setActivity("führt "+KINDS[s.kind()]);
  if(!JobUtil.approach(c,at(s,0,1,0),2)) return true;
  c.v().getLookControl().setLookAt(counter(s).getX()+.5,c.v().getEyeY(),counter(s).getZ()+.5);
  restock(c,s); refreshDisplays(c.level(),c.village(),s); c.st().cooldownUntil=c.now()+80;return true;
 }
 static Item[] goods(int kind) { return switch(kind) {
  case 0 -> new Item[]{Items.BREAD,Items.CARROT,Items.POTATO,Items.BEETROOT,Items.APPLE,Items.WHEAT,Items.WHEAT_SEEDS};
  case 1 -> new Item[]{Items.WOOL.white(),Items.LEATHER,Items.STRING};
  default -> new Item[]{Items.IRON_PICKAXE,Items.IRON_AXE,Items.IRON_SHOVEL,Items.IRON_HOE,
   Items.OAK_PLANKS,Items.SPRUCE_PLANKS,Items.COAL,Items.IRON_INGOT}; }; }
 private static boolean handTool(Item item) {
  return item==Items.IRON_PICKAXE||item==Items.IRON_AXE||item==Items.IRON_SHOVEL||item==Items.IRON_HOE;
 }
 public static List<Container> wallet(ServerLevel level,Villager v) {
  List<Container> stores=new ArrayList<>();stores.add(v.getInventory());
  var mem=v.getAttachedOrCreate(VillagerPlus.MEMORY);
  if(mem.homeChest.isPresent() && ownedWalletChest(level,mem.homeChest.get(),v.getUUID())) {
   Container box=HopperBlockEntity.getContainerAt(level,mem.homeChest.get());if(box!=null) stores.add(box);
  } return stores;
 }
 private static boolean ownedWalletChest(ServerLevel level,BlockPos pos,UUID owner) {
  if(!level.hasChunkAt(pos) || !Ownership.isOwnedBy(level,pos,owner)) return false;
  var state=level.getBlockState(pos);
  if(!(state.getBlock() instanceof ChestBlock)) return false;
  return state.getValue(ChestBlock.TYPE)==net.minecraft.world.level.block.state.properties.ChestType.SINGLE
    || Ownership.isOwnedBy(level,pos.relative(ChestBlock.getConnectedDirection(state)),owner);
 }
 /** Rent is charged once when a persisted shop career is assigned; no stand is created on failed payment. */
 static boolean payRent(ServerLevel level,VillageRef ref,Villager owner) {
  return chargeTreasury(level,ref,owner,wallet(level,owner),STALL_RENT);
 }
 static Container stock(ServerLevel l,MarketData.Shop s) {
  if(l==null||s==null)return null;
  BlockPos pos=stockPos(s);
  if(!l.hasChunkAt(pos)||!(l.getBlockEntity(pos) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity chest)
    ||!s.owner().equals(chest.getAttached(VillagerPlus.OWNER)))return null;
  var state=l.getBlockState(pos);
  if(!(state.getBlock() instanceof ChestBlock)||state.getValue(ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE)return null;
  return chest;
 }
 private static int count(List<Container> boxes,Item item) { int n=0;for(Container b:boxes)n+=JobUtil.count(b,st->st.is(item)&&st.getComponents().equals(new ItemStack(item).getComponents()));return n; }
 private static int take(List<Container> boxes,Item item,int n) {int taken=0;for(Container b:boxes) { if(n<=0)break;int removed=JobUtil.take(b,st->st.is(item)&&st.getComponents().equals(new ItemStack(item).getComponents()),n);n-=removed;taken+=removed;}return taken;}
 /** Charges real wallet emeralds into the persistent cash ledger instead of a material-only construction depot. */
 private static boolean chargeTreasury(ServerLevel level,VillageRef ref,Villager payer,List<Container> funds,int amount) {
  if(amount<=0 || count(funds,Items.EMERALD)<amount || !Network.creditTreasuryEmeralds(ref,amount)) return false;
  int removed=take(funds,Items.EMERALD,amount);
  if(removed==amount) return true;
  if(!Network.spendTreasuryEmeralds(ref,amount)) throw new IllegalStateException("Market treasury payment rollback failed");
  if(removed>0) {
   ItemStack refund=new ItemStack(Items.EMERALD,removed);
   for(Container box:funds) { refund=HopperBlockEntity.addItem(null,box,refund,null); if(refund.isEmpty()) break; }
   if(!refund.isEmpty()) {
    var dropped=new net.minecraft.world.entity.item.ItemEntity(level,payer.getX(),payer.getY()+0.5,payer.getZ(),refund);
    dropped.setDefaultPickUpDelay();level.addFreshEntity(dropped);
   }
  }
  return false;
 }
 private static boolean fits(Container box,ItemStack s) {int cap=0;for(int i=0;i<box.getContainerSize();i++){var a=box.getItem(i);if(a.isEmpty())cap+=s.getMaxStackSize();else if(ItemStack.isSameItemSameComponents(a,s))cap+=Math.max(0,a.getMaxStackSize()-a.getCount());}return cap>=s.getCount();}
 private static boolean add(List<Container> boxes,ItemStack s) {
  for(Container b:boxes)if(fits(b,s)) {JobUtil.depositTo(b,s.copy());return true;} return false;
 }
 static void restock(Ctx c,MarketData.Shop s) {
  Container shelf=stock(c.level(),s);if(shelf==null)return;
  List<Container> funds=wallet(c.level(),c.v()); if(count(funds,Items.EMERALD)<1)return;
  for(Item item:goods(s.kind())) {
   int unit=item==Items.IRON_INGOT||handTool(item)?1:4;
   int requested=item==Items.IRON_INGOT?2:handTool(item)?1:8;
   int stocked=count(List.of(shelf),item);
   int quantity=Math.min(requested,((STOCK_CAP-stocked)/unit)*unit);
   if(quantity<unit || !fits(shelf,new ItemStack(item,quantity)))continue;
   List<Container> pool=Balance.storage(c.level(),c.village()); // Dorfkiste, Lager, Kornspeicher, Werkzeugkammer
   int reserve=handTool(item)?1:16;
   if(count(pool,item)<quantity+reserve)continue;
   // Owner buys stock with private money, the community receives payment.
   if(!chargeTreasury(c.level(),c.village(),c.v(),funds,1)) continue;
   take(pool,item,quantity);
   JobUtil.depositTo(shelf,new ItemStack(item,quantity));
   MarketDisplays.sync(c.level(),c.village(),s,shelf);
   Anim.hold(c,item);JobUtil.swing(c.v());return;
  }
 }
 // --- Spielerkauf: Rechtsklick auf den Ladeninhaber oeffnet das Handelsfenster mit dem ECHTEN Warenbestand der Ladenkiste. ---
 private record Session(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,BlockPos bell,net.minecraft.world.item.trading.MerchantOffers original,
  net.minecraft.world.item.trading.MerchantOffers offers,int[] uses,int[] quantity,int[] cost,Item[] items) {}
 private static final Map<UUID,Session> SESSIONS=new HashMap<>();
 public static void registerPlayerShop() {
  net.fabricmc.fabric.api.event.player.UseEntityCallback.EVENT.register((player,world,hand,entity,hit)->{
   if(!(world instanceof ServerLevel level)||hand!=net.minecraft.world.InteractionHand.MAIN_HAND||player.isSpectator()||player.isShiftKeyDown()||!(entity instanceof Villager seller)||seller.isBaby()||seller.isSleeping())
    return net.minecraft.world.InteractionResult.PASS;
   var ref=Villages.find(level,seller); if(ref==null)return net.minecraft.world.InteractionResult.PASS;
   var shop=shop(ref,seller.getUUID()); if(shop==null||shop.opened()==0||shop.deceased()||seller.getTradingPlayer()!=null||SESSIONS.containsKey(seller.getUUID()))return net.minecraft.world.InteractionResult.PASS;
   Container shelf=stock(level,shop); if(shelf==null)return net.minecraft.world.InteractionResult.PASS;
   String owner=seller.getAttachedOrCreate(VillagerPlus.MEMORY).name; String title=KINDS[Math.floorMod(shop.kind(),3)]+(owner.isEmpty()?"":" von "+owner);
   Map<Item,Integer> counts=new LinkedHashMap<>();
   for(int i=0;i<shelf.getContainerSize();i++){var st=shelf.getItem(i);if(!st.isEmpty()&&st.getComponents().equals(new ItemStack(st.getItem()).getComponents()))counts.merge(st.getItem(),st.getCount(),Integer::sum);}
   var offers=new net.minecraft.world.item.trading.MerchantOffers();List<Item> items=new ArrayList<>();List<Integer> quantities=new ArrayList<>(),costs=new ArrayList<>();
   for(var e:counts.entrySet()) {
    int unit=Caravan.value(e.getKey());if(unit<=0)unit=2;
    int q=e.getKey()==Items.IRON_INGOT?1:Math.min(4,e.getValue());if(e.getValue()<q)continue;
    int cost=Math.max(1,(int)Math.ceil(unit*q*1.3/64.0));
    offers.add(new net.minecraft.world.item.trading.MerchantOffer(new net.minecraft.world.item.trading.ItemCost(Items.EMERALD,cost),new ItemStack(e.getKey(),q),e.getValue()/q,0,0.0F));
    items.add(e.getKey());quantities.add(q);costs.add(cost);
   }
   if(offers.isEmpty()) { VillageMessages.system(player, Component.literal((owner.isEmpty()?"Der Ladeninhaber":owner)+": Heute ist leider alles ausverkauft.")); return net.minecraft.world.InteractionResult.SUCCESS; }
   var original=new net.minecraft.world.item.trading.MerchantOffers();original.addAll(seller.getOffers());
   seller.getOffers().clear();seller.getOffers().addAll(offers);
   SESSIONS.put(seller.getUUID(),new Session(level.dimension(),ref.bell(),original,offers,new int[offers.size()],quantities.stream().mapToInt(Integer::intValue).toArray(),
    costs.stream().mapToInt(Integer::intValue).toArray(),items.toArray(new Item[0])));
   seller.setTradingPlayer(player);
   seller.openTradingScreen(player,Component.literal(title),1);
   return net.minecraft.world.InteractionResult.SUCCESS;
  });
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(Market::settleSessions);
  net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server->{
   for(var level:server.getAllLevels()) for(var en:new ArrayList<>(SESSIONS.entrySet())) if(en.getValue().dimension().equals(level.dimension())&&level.getEntity(en.getKey()) instanceof Villager v) endSession(level,v,en.getValue());
   SESSIONS.clear();
  });
 }
 /** Verbuchte Spielerkaeufe: Ware aus der Ladenkiste nehmen, Smaragde an den Inhaber, Journal und Umsatz. */
 private static void settleSessions(ServerLevel level) {
  if(SESSIONS.isEmpty()||level.getGameTime()%5!=0)return;
  for(var en:new ArrayList<>(SESSIONS.entrySet())) {
   Session s=en.getValue();if(!s.dimension().equals(level.dimension()))continue;
   if(!(level.getEntity(en.getKey()) instanceof Villager seller)||!seller.isAlive()){SESSIONS.remove(en.getKey());continue;}
   settle(level,seller,s);
   if(seller.getTradingPlayer()==null||seller.getTradingPlayer().isRemoved()) { endSession(level,seller,s);SESSIONS.remove(en.getKey()); }
  }
 }
 private static void settle(ServerLevel level,Villager seller,Session s) {
  var ref=Villages.find(level,seller);if(ref==null)return;var shop=shop(ref,seller.getUUID());if(shop==null)return;
  Container shelf=stock(level,shop);
  for(int i=0;i<s.offers().size();i++) {
   int uses=s.offers().get(i).getUses(),delta=uses-s.uses()[i];if(delta<=0)continue;s.uses()[i]=uses;
   if(shelf!=null) {
    take(List.of(shelf),s.items()[i],delta*s.quantity()[i]);
    MarketDisplays.sync(level,ref,shop,shelf);
   }
   int emeralds=delta*s.cost()[i];
   while(emeralds>0){int n=Math.min(64,emeralds);if(!add(wallet(level,seller),new ItemStack(Items.EMERALD,n))){level.addFreshEntity(new net.minecraft.world.entity.item.ItemEntity(level,seller.getX(),seller.getY()+.5,seller.getZ(),new ItemStack(Items.EMERALD,n)));}emeralds-=n;}
   var sm=seller.getAttachedOrCreate(VillagerPlus.MEMORY);sm.jobsDone+=delta;
   MarketData m=of(ref);save(ref,new MarketData(m.plaza(),m.shops(),m.nextPlan(),m.sales()+delta));
   Journal.add(level,ref,"Handel","Laden "+sm.name,"Spieler kauft "+delta*s.quantity()[i]+" "+new ItemStack(s.items()[i]).getHoverName().getString()+" für "+delta*s.cost()[i]+" Smaragd(e)",64L*delta);
   level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,seller.getX(),seller.getY()+1.5,seller.getZ(),5,.2,.2,.2,0);
  }
 }
 private static void endSession(ServerLevel level,Villager seller,Session s) {
  settle(level,seller,s);
  seller.getOffers().clear();seller.getOffers().addAll(s.original());
  if(seller.getTradingPlayer()!=null)seller.setTradingPlayer(null);
 }
 /** Customers visit during breaks, carrying real money and choosing goods they actually lack. */
 public static boolean visit(Ctx c) {
  if(!open(c.level()) || c.village()==null || shop(c.village(),c.v().getUUID())!=null || c.now()<c.v().getAttachedOrElse(VillagerPlus.LAST_SHOP,0L)+2400)return false;
  List<Container> funds=wallet(c.level(),c.v());if(count(funds,Items.EMERALD)==0)return false;
  for(MarketData.Shop s:of(c.village()).shops()) {
   if(s.deceased() || s.opened()==0 || !(c.level().getEntity(s.owner()) instanceof Villager seller)
     || seller.isBaby() || seller.isSleeping() || seller.getTradingPlayer()!=null)continue;
   Container shelf=stock(c.level(),s);if(shelf==null)continue;
   for(Item item:goods(s.kind())) {
    int shelfStock=JobUtil.count(shelf,a->a.is(item));
    if(shelfStock<1)continue;
    int breedingShortage=s.kind()==0?Livestock.marketFeedShortage(c.level(),c.village(),c.v(),item):0;
    int target=handTool(item)?1:4;
    if(breedingShortage==0 && count(funds,item)>=target)continue;
    if(s.kind()==0 && ((item==Items.WHEAT || item==Items.WHEAT_SEEDS) && breedingShortage==0
      || item!=Items.WHEAT && item!=Items.WHEAT_SEEDS && breedingShortage==0
       && Wellbeing.of(c.level(),c.v(),c.mem()).satiety()>75))continue;
    int quantity=breedingShortage>0?breedingShortage:(item==Items.IRON_INGOT||handTool(item)?1:4);
    int price=handTool(item)?2:1;
    if(shelfStock<quantity || !fits(c.v().getInventory(),new ItemStack(item,quantity)))continue;
    if(count(funds,Items.EMERALD)<price)continue;
    List<Container> proceeds=wallet(c.level(),seller);
    if(proceeds.stream().noneMatch(b->fits(b,new ItemStack(Items.EMERALD,price))))continue;
    c.setActivity("kauft bei "+seller.getName().getString());
    BlockPos stand=JobUtil.sharedStandSpot(c,counter(s),seller.position(),6.0);
    if(stand==null) { c.st().cooldownUntil=Math.max(c.st().cooldownUntil,c.now()+40); return true; }
    if(!JobUtil.approachShared(c,stand,1.0))return true;
    if(seller.position().distanceToSqr(c.v().position())>36)continue;
    if(!purchase(c,seller,s,item,quantity,price)) continue;
    c.v().getLookControl().setLookAt(seller,30,30);seller.getLookControl().setLookAt(c.v(),30,30);
    Anim.hold(c,item);JobUtil.swing(c.v());JobUtil.swing(seller);
    c.st().cooldownUntil=c.now()+60;return true;
   }
  }
  return false;
 }
 /** Atomic villager-to-villager purchase using the actual private inventories and owned stall chest. */
 static boolean purchase(Ctx c,Villager seller,MarketData.Shop shop,Item item,int quantity,int price) {
  if(quantity<=0 || price<=0 || seller==c.v() || seller.getTradingPlayer()!=null) return false;
  Container shelf=stock(c.level(),shop); if(shelf==null) return false;
  List<Container> buyerFunds=wallet(c.level(),c.v()), sellerFunds=wallet(c.level(),seller);
  if(count(buyerFunds,Items.EMERALD)<price || count(List.of(shelf),item)<quantity
    || !fits(c.v().getInventory(),new ItemStack(item,quantity))) return false;
  boolean sellerHasRoom=sellerFunds.stream().anyMatch(box->fits(box,new ItemStack(Items.EMERALD,price)));
  if(!sellerHasRoom) return false;
  // All preconditions are checked before either side is changed.
  take(buyerFunds,Items.EMERALD,price); take(List.of(shelf),item,quantity);
  if(!add(sellerFunds,new ItemStack(Items.EMERALD,price))) {
   // Capacity cannot change between validation and insertion on the server thread.
   throw new IllegalStateException("Private market wallet capacity changed during purchase");
  }
  JobUtil.depositTo(c.v().getInventory(),new ItemStack(item,quantity));
  MarketDisplays.sync(c.level(),c.village(),shop,shelf);
  c.v().setAttached(VillagerPlus.LAST_SHOP,c.now()); c.mem().lastChat=c.now();c.mem().chats++;
  var sm=seller.getAttachedOrCreate(VillagerPlus.MEMORY);sm.lastChat=c.now();sm.jobsDone++;
  Journal.add(c.level(),c.village(),"Handel","Laden "+sm.name,c.mem().name+" kauft "+quantity+" "+new ItemStack(item).getHoverName().getString()+" bei "+sm.name+" für "+price+" Smaragd(e)",64);
  MarketData m=of(c.village());save(c.village(),new MarketData(m.plaza(),m.shops(),m.nextPlan(),m.sales()+1));
  c.level().sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,c.v().getX(),c.v().getY()+1.5,c.v().getZ(),5,.2,.2,.2,0);
  return true;
 }
 public static List<String> lines(VillageRef ref) {
  MarketData m=of(ref);List<String> out=new ArrayList<>();out.add("Marktplatz: "+m.plaza().map(BlockPos::toShortString).orElse("sucht freie Fläche mit Abstand zu Häusern"));
  out.add("Läden: "+m.shops().size()+" · Käufe der Einwohner: "+m.sales());
  for(var s:m.shops())out.add(KINDS[s.kind()]+": "+(s.opened()>0?"eröffnet":"Bau / Ausbildung")+" bei "+s.origin().toShortString());return out;
 }
}
