package de.villagerplus.village;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.Blocks;

/** Collision regression on a dedicated remote synthetic platform. */
public final class CaravanSiteChecks {
 private CaravanSiteChecks() { }
 public static int run(ServerLevel level,BlockPos at) {
  var originalBlocks=new java.util.LinkedHashMap<BlockPos,BlockSnapshot>();
  try {
  for(int x=-10;x<=10;x++)for(int z=-10;z<=10;z++) {
   BlockPos p=at.offset(x,0,z);level.getChunkAt(p);fixtureSetBlock(level,originalBlocks,p.below(),Blocks.STONE.defaultBlockState());
   for(int y=0;y<=5;y++)fixtureSetBlock(level,originalBlocks,p.above(y),Blocks.AIR.defaultBlockState());
  }
  var golem=EntityTypes.IRON_GOLEM.create(level,EntitySpawnReason.COMMAND);
  var trader=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.create(level,EntitySpawnReason.COMMAND);
  int checks=0;
  BlockPos camp=at.offset(-25,1,25);
  for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++) {
   BlockPos floor=camp.offset(x,-1,z);level.getChunkAt(floor);fixtureSetBlock(level,originalBlocks,floor,Blocks.STONE.defaultBlockState());
   for(int y=0;y<=4;y++)fixtureSetBlock(level,originalBlocks,camp.offset(x,y,z),Blocks.AIR.defaultBlockState());
  }
  List<BlockPos> campCells=List.of(camp.offset(-1,0,-1),camp.offset(-1,1,-1),camp.offset(0,3,0),camp.offset(3,0,0));
  check(CaravanSites.canPlaceCampAt(level,camp,campCells),"complete flat air-only campsite footprint is accepted");checks++;
  fixtureSetBlock(level,originalBlocks,camp.offset(0,1,0),Blocks.OAK_PLANKS.defaultBlockState());
  check(!CaravanSites.canPlaceCampAt(level,camp,campCells),"occupied canopy space rejects the whole campsite instead of producing a partial tent");checks++;
  fixtureSetBlock(level,originalBlocks,camp.offset(0,1,0),Blocks.AIR.defaultBlockState());
  fixtureSetBlock(level,originalBlocks,camp.offset(3,0,3),Blocks.STONE.defaultBlockState());
  check(!CaravanSites.canPlaceCampAt(level,camp,campCells),"uneven ground across the full tent-and-fire pad is rejected");checks++;
  fixtureSetBlock(level,originalBlocks,camp.offset(3,0,3),Blocks.AIR.defaultBlockState());
  check(CaravanSites.canStand(level,golem,at,List.of()),"flat supported site fits complete golem box");checks++;
  fixtureSetBlock(level,originalBlocks,at.above(2),Blocks.STONE.defaultBlockState());
  check(CaravanSites.canStand(level,trader,at,List.of())&&!CaravanSites.canStand(level,golem,at,List.of()),"two-block headroom fitting trader must reject tall golem");checks++;
  fixtureSetBlock(level,originalBlocks,at.above(2),Blocks.AIR.defaultBlockState());fixtureSetBlock(level,originalBlocks,at.east(),Blocks.STONE.defaultBlockState());
  check(CaravanSites.canStand(level,trader,at,List.of())&&!CaravanSites.canStand(level,golem,at,List.of()),"side wall outside trader box still intersects golem width");checks++;
  fixtureSetBlock(level,originalBlocks,at.east(),Blocks.AIR.defaultBlockState());fixtureSetBlock(level,originalBlocks,at.east().below(),Blocks.AIR.defaultBlockState());
  check(CaravanSites.canStand(level,trader,at,List.of())&&!CaravanSites.canStand(level,golem,at,List.of()),"golem requires support across full footprint");checks++;
  fixtureSetBlock(level,originalBlocks,at.east().below(),Blocks.STONE.defaultBlockState());fixtureSetBlock(level,originalBlocks,at,Blocks.WATER.defaultBlockState());
  check(!CaravanSites.canStand(level,golem,at,List.of()),"water inside the body is rejected");checks++;
  fixtureSetBlock(level,originalBlocks,at,Blocks.AIR.defaultBlockState());
  check(!CaravanSites.canStand(level,golem,at,List.of(CaravanSites.bounds(trader,at))),"reserved trader slot excludes overlapping guard");checks++;
  for(int y=0;y<3;y++)fixtureSetBlock(level,originalBlocks,at.offset(2,y,-1),Blocks.STONE.defaultBlockState());
  var group=CaravanSites.plan(level,at);
  check(group!=null&&!group.guard().equals(at.offset(2,0,-1)),"blocked legacy offset chooses a different safe guard position");checks++;
  var animal=EntityTypes.DONKEY.create(level,EntitySpawnReason.COMMAND);
  var boxes=new java.util.ArrayList<net.minecraft.world.phys.AABB>();boxes.add(CaravanSites.bounds(trader,group.trader()));boxes.add(CaravanSites.bounds(golem,group.guard()));
  for(BlockPos p:group.donkeys())boxes.add(CaravanSites.bounds(animal,p));
  for(int a=0;a<boxes.size();a++)for(int b=a+1;b<boxes.size();b++)check(!boxes.get(a).intersects(boxes.get(b)),"whole caravan reserves nonoverlapping slots");checks++;
  for(int y=0;y<3;y++)fixtureSetBlock(level,originalBlocks,at.offset(2,y,-1),Blocks.AIR.defaultBlockState());
  for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)fixtureSetBlock(level,originalBlocks,at.offset(x,2,z),Blocks.STONE.defaultBlockState());
  check(CaravanSites.plan(level,at)==null,"no complete group is offered when every guard position is blocked");checks++;
  for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)fixtureSetBlock(level,originalBlocks,at.offset(x,2,z),Blocks.AIR.defaultBlockState());
  var spawned=EntityTypes.IRON_GOLEM.spawn(level,group.guard(),EntitySpawnReason.COMMAND);
  try {
   check(spawned!=null&&CaravanSites.canStand(level,spawned,spawned.blockPosition(),List.of()),"real spawned guard passes post-spawn collision validation");checks++;
   check(!CaravanSites.canPlaceCampAt(level,camp,List.of(spawned.blockPosition())),"living entity in a camp cell blocks the complete placement");checks++;
   spawned.setNoAi(true);spawned.addTag("vp090_fixture");
   check(!CaravanSites.campCellFree(level,spawned.blockPosition().above(2)),"camp canopy may not occupy the guard's upper body");checks++;
   check(!CaravanSites.campCellFree(level,spawned.blockPosition()),"camp posts may not enclose the guard's feet");checks++;
  } finally {if(spawned!=null)spawned.discard();}
  fixtureSetBlock(level,originalBlocks,at.east(8),Blocks.BARREL.defaultBlockState());
  check(!CaravanSites.campCellFree(level,at.east(8)),"existing block entity is never a camp placement cell");checks++;
  // The later donkey route fixture starts here; do not leave the collision-test barrel in its feet cell.
  fixtureSetBlock(level,originalBlocks,at.east(8),Blocks.AIR.defaultBlockState());
  BlockPos far=new BlockPos(1100000,280,1100000);
  check(!level.hasChunkAt(far)&&!CaravanSites.canStand(level,golem,far,List.of())&&!level.hasChunkAt(far),"placement rejection does not load remote chunks");checks++;
  check(!level.hasChunkAt(far)&&!CaravanSites.canPlaceCampAt(level,far,List.of(far))&&!level.hasChunkAt(far),"camp footprint rejection does not load remote chunks");checks++;
  var ref=de.villagerplus.dev.Checks090.fixtureForEquipment(level,at);
  BlockPos depotFixture=at.offset(0,0,8);
  BlockPos blockEntityFloor=depotFixture.offset(0,0,-5);
  BlockSnapshot oldDepot=BlockSnapshot.capture(level,depotFixture);
  BlockSnapshot oldBlockEntityFloor=BlockSnapshot.capture(level,blockEntityFloor);
  BlockSnapshot[] oldBuildingCell={null};
  try {
   fixtureSetBlock(level,originalBlocks,depotFixture,Blocks.CHEST.defaultBlockState());
   fixtureSetBlock(level,originalBlocks,blockEntityFloor,Blocks.CHEST.defaultBlockState());
   trader.setPos(at.getX()+.5,at.getY(),at.getZ()+.5);
   BlockPos depotStand=Caravan.safeDepotArrivalStand(level,trader,depotFixture);
   check(depotStand!=null&&!depotStand.equals(depotFixture)&&level.hasChunkAt(depotStand)
    &&CaravanSites.canStand(level,trader,depotStand,List.of())
    &&Caravan.hasSafeTraderStand(level,trader,depotStand)
    &&!depotStand.equals(blockEntityFloor.above())
    &&!CaravanSites.bounds(trader,depotStand).intersects(new net.minecraft.world.phys.AABB(depotFixture))
    &&Math.pow(depotStand.getX()-depotFixture.getX(),2)+Math.pow(depotStand.getZ()-depotFixture.getZ(),2)>=25
    &&Math.pow(depotStand.getX()-depotFixture.getX(),2)+Math.pow(depotStand.getZ()-depotFixture.getZ(),2)<=49,
    "merchant arrival picks a clear ground stand five to seven horizontal blocks from the depot and skips chest tops");checks++;
   check(!Caravan.hasSafeTraderStand(level,trader,blockEntityFloor.above()),
    "merchant footprint rejects a block entity used as its supporting floor");checks++;
   BlockPos buildingCell=depotStand.above();
   oldBuildingCell[0]=BlockSnapshot.capture(level,buildingCell);
   fixtureSetBlock(level,originalBlocks,buildingCell,Blocks.STONE.defaultBlockState());
   check(!Caravan.hasSafeTraderStand(level,trader,depotStand),
    "merchant footprint rejects a building block occupying its body space");checks++;
  } finally {
   if(oldBuildingCell[0]!=null)oldBuildingCell[0].restore(level);
   oldBlockEntityFloor.restore(level);
   oldDepot.restore(level);
  }
  // Continuous fallback arrival ring for the public spawn entry point; no RNG seed manipulation.
  for(int x=-49;x<=49;x++)for(int z=-49;z<=49;z++) {
   double radius=Math.hypot(x,z);if(radius<31||radius>49)continue;
   BlockPos p=at.offset(x,0,z);level.getChunkAt(p);fixtureSetBlock(level,originalBlocks,p.below(),Blocks.STONE.defaultBlockState());
   for(int y=0;y<5;y++)fixtureSetBlock(level,originalBlocks,p.above(y),Blocks.AIR.defaultBlockState());
  }
  // Give navigation its own clean synthetic area. Earlier failed versions of this
  // check could leave the depot chest at the old test origin; keep it intact and
  // never let that fixture residue become a route obstacle here.
  BlockPos guideAt=findGuideFixtureArea(level,at);
  // Supply a real, reversible floor across the complete navigation envelope.
  var guideFloorOriginal=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
  var guideClearanceOriginal=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
  de.villagerplus.entity.CaravanTraderEntity guideTrader=null;
  de.villagerplus.entity.CaravanTraderEntity externalHolder=null;
  net.minecraft.world.entity.animal.equine.Donkey guideDonkey=null;
  try {
  for(int x=-16;x<=16;x++)for(int z=-16;z<=16;z++) {
   BlockPos floor=guideAt.offset(x,-1,z);
   level.getChunkAt(floor);
   var support=level.getBlockState(floor);
   boolean unusable=!support.getFluidState().isEmpty()||!support.isFaceSturdy(level,floor,net.minecraft.core.Direction.UP)
    ||support.is(Blocks.MAGMA_BLOCK)||support.is(Blocks.CACTUS)||support.is(Blocks.CAMPFIRE)||support.is(Blocks.SOUL_CAMPFIRE);
   if(unusable&&level.getBlockEntity(floor)==null) {
    guideFloorOriginal.putIfAbsent(floor,support);
    fixtureSetBlock(level,originalBlocks,floor,Blocks.STONE.defaultBlockState());
   }
  }
  for(int x=-4;x<=12;x++)for(int z=-4;z<=7;z++)for(int y=0;y<=3;y++) {
   BlockPos clear=guideAt.offset(x,y,z);
   var state=level.getBlockState(clear);
   if((!state.getCollisionShape(level,clear).isEmpty()||!state.getFluidState().isEmpty())
    &&level.getBlockEntity(clear)==null) {
    guideClearanceOriginal.putIfAbsent(clear,state);
    fixtureSetBlock(level,originalBlocks,clear,Blocks.AIR.defaultBlockState());
   }
  }
  guideTrader=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.spawn(level,guideAt,EntitySpawnReason.COMMAND);
  externalHolder=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.spawn(level,guideAt,EntitySpawnReason.COMMAND);
  guideDonkey=EntityTypes.DONKEY.spawn(level,guideAt.east(8),EntitySpawnReason.COMMAND);
  if(guideTrader==null||externalHolder==null||guideDonkey==null)throw new IllegalStateException("Caravan site: cannot spawn departure navigation fixture");
  // Keep the control trader used by the later ownership case off the path goal.
  externalHolder.teleportTo(guideAt.getX()+.5,guideAt.getY(),guideAt.getZ()+5.5);
   var guideState=new Caravan.State(guideTrader,level,ref,guideAt,-1);
   Caravan.registerActiveStateForChecks(guideState);
   guideState.llamas.add(guideDonkey.getUUID());
   guideDonkey.setLeashedTo(guideTrader,true);
  var timeoutState=new Caravan.State(guideTrader,level,ref,at,-1);
  long afterVisitTimeout=timeoutState.born+12001L;
  check(Caravan.shouldExpireWithoutCargo(timeoutState,afterVisitTimeout,false),
   "an untraded, cargo-free stale visit remains eligible for timeout cleanup");checks++;
  timeoutState.traded=true;
  timeoutState.leaveAt=timeoutState.born+1L;
  check(!Caravan.shouldExpireWithoutCargo(timeoutState,afterVisitTimeout,false),
   "a due return trip cannot expire and discard its empty pack animals before they arrive");checks++;
  var donkeyChest=guideDonkey.getSlot(499);
  if(donkeyChest==null||!donkeyChest.set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CHEST))||!guideDonkey.hasChest())
   throw new IllegalStateException("Caravan site: cannot equip cargo chest on departure fixture donkey");
   var waitSpot=Caravan.safePackWaitSpot(level,guideTrader);
   check(waitSpot!=null&&level.hasChunkAt(waitSpot)&&CaravanSites.canStand(level,guideTrader,waitSpot,java.util.List.of()),
    "merchant waiting spot is supported and already loaded");checks++;
   var cargoSlot=guideDonkey.getSlot(502);
   if(cargoSlot==null)throw new IllegalStateException("Caravan site: donkey fixture has no real cargo slot");
   cargoSlot.set(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS,7));
   // EntitySpawnReason.COMMAND inserts immediately but does not run the first physics
   // tick. GroundPathNavigation refuses createPath until onGround/liquid/passenger.
   guideDonkey.setOnGround(true);
   var before=guideDonkey.position();
   BlockPos donkeyApproach=Caravan.safePackDonkeyApproach(level,guideDonkey,guideTrader);
   var donkeyPath=donkeyApproach==null?null:guideDonkey.getNavigation().createPath(donkeyApproach,1);
   check(donkeyPath!=null&&donkeyPath.canReach(),"settled loaded donkey has a reachable navigation path [goal="+donkeyApproach
    +", nodes="+(donkeyPath==null?"none":donkeyPath.getNodeCount())+"]");checks++;
   guideDonkey.teleportTo(guideAt.getX()+6.5,guideAt.getY(),guideAt.getZ()+.5);
   guideState.nextDonkeyFollowAt=0;
   Caravan.guideVisitingDonkeys(level,guideState,guideTrader,Math.max(1L,level.getGameTime()+1));
   check(guideDonkey.getNavigation().isInProgress()&&guideDonkey.position().distanceToSqr(guideTrader.position())>9.0
    &&guideDonkey.getLeashHolder()==guideTrader&&guideState.departurePathReady,
    "a distant visit donkey receives a safe route to the trader without replacing its lead or departure status");checks++;
   check(cargoSlot.get().is(net.minecraft.world.item.Items.OAK_PLANKS)&&cargoSlot.get().getCount()==7,
    "visit guidance leaves real donkey cargo untouched");checks++;
   guideDonkey.getNavigation().stop();
   guideState.nextDonkeyFollowAt=0;
   var departureStart=guideDonkey.position();
   BlockPos departureApproach=Caravan.safePackDonkeyApproach(level,guideDonkey,guideTrader);
   var departurePath=departureApproach==null?null:guideDonkey.getNavigation().createPath(departureApproach,1);
   String departurePathValidation=Caravan.donkeyPathFailureReason(level,guideDonkey,departurePath,departureApproach);
   String departurePathNodes=departurePath==null?"none":java.util.stream.IntStream.range(0,departurePath.getNodeCount())
    .mapToObj(departurePath::getNodePos).toList().toString();
   check(departureApproach!=null&&departurePath!=null&&departurePath.canReach()&&departurePathValidation==null,
    "current departure start has a validated normal path [start="+departureStart+", goal="+departureApproach
     +", validator="+departurePathValidation+", nodes="+departurePathNodes+"]");checks++;
   long departureGuidanceStart=Math.max(1L,level.getGameTime());
   Caravan.guideDepartingDonkeys(level,guideState,guideTrader,departureGuidanceStart);
   String pathValidation=departurePathValidation;
   String pathNodes=departurePathNodes;
    check(departureApproach!=null&&CaravanSites.canStand(level,guideDonkey,departureApproach,java.util.List.of(guideTrader.getBoundingBox()))
     &&guideDonkey.getNavigation().isInProgress()&&guideDonkey.position().equals(departureStart),
    "registered pack donkey receives a physical route toward the trader without teleporting [start="+guideDonkey.blockPosition()
     +", startBlock="+level.getBlockState(guideDonkey.blockPosition())+", goal="+donkeyApproach
     +", goalBlock="+(donkeyApproach==null?"missing":level.getBlockState(donkeyApproach))+", leash="+guideDonkey.getLeashHolder()
     +", validator="+pathValidation+", nodes="+pathNodes+"]");checks++;
   check(guideDonkey.getLeashHolder()==guideTrader&&cargoSlot.get().is(net.minecraft.world.item.Items.OAK_PLANKS)&&cargoSlot.get().getCount()==7,"departure guidance preserves the real leash and all carried building material");checks++;
   double separationBeforeWalking=guideDonkey.distanceToSqr(guideTrader);
   var previousFooting=guideDonkey.position();
   double largestStepSqr=0.0;
   for(int step=0;step<40;step++) {
    guideDonkey.tick();
    // Caravan guidance refreshes every 20 server ticks. This fixture ticks only the
    // donkey directly, so advance that production cadence explicitly rather than
    // expecting one navigation command to replace the live caravan ticker.
    if((step+1)%20==0) Caravan.guideDepartingDonkeys(level,guideState,guideTrader,departureGuidanceStart+step+1L);
    var currentFooting=guideDonkey.position();
    largestStepSqr=Math.max(largestStepSqr,currentFooting.distanceToSqr(previousFooting));
    previousFooting=currentFooting;
   }
   check(guideDonkey.distanceToSqr(guideTrader)<separationBeforeWalking&&largestStepSqr<1.0
    &&cargoSlot.get().is(net.minecraft.world.item.Items.OAK_PLANKS)&&cargoSlot.get().getCount()==7
    &&guideDonkey.getLeashHolder()==guideTrader,
    "repeated real donkey ticks move it toward the travelling merchant without a teleport, cargo loss, or lead replacement");checks++;
   double departureDistance=guideDonkey.distanceToSqr(guideTrader);
   check(Caravan.packAnimalsCanKeepUp(level,guideState,guideTrader),"a nearby live pack donkey does not pause departure [distance="
    +departureDistance+", navigation="+guideDonkey.getNavigation().isInProgress()+", pathReady="+guideState.departurePathReady+"]");checks++;
    // A clear Euclidean distance is not enough: when a loaded pack animal cannot
    // path out, the trader must wait instead of leaving it on the old side of terrain.
    String routeDimension=Network.dim(level);
    BlockPos routeBell=guideAt.west(80).immutable();
    guideState.routeDimension=routeDimension;
    guideState.routeBell=routeBell;
    guideState.routeRelationDelta=6;
    guideState.routeVisitDay=19L;
    guideState.roadKey=routeDimension+routeBell.asLong();
    guideState.roadCursor=3;
    // The preceding movement fixture intentionally walked the donkey close to the
    // trader. Start this independent blocked-route case far enough from home to ensure
    // it has not already satisfied the physical arrival gate.
    guideDonkey.teleportTo(before.x,before.y,before.z);
    // This fixture teleport bypasses the normal ground-collision update. A real donkey
    // stays on the restored floor; preserve that state so navigation can be retried.
    guideDonkey.setOnGround(true);
    guideDonkey.getNavigation().stop();
    var cageOriginal=new java.util.LinkedHashMap<BlockPos,net.minecraft.world.level.block.state.BlockState>();
    BlockPos cageCenter=guideDonkey.blockPosition();
    try {
     for(int y=0;y<3;y++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
      if(Math.max(Math.abs(dx),Math.abs(dz))==2) {
       BlockPos wall=cageCenter.offset(dx,y,dz);
       cageOriginal.putIfAbsent(wall,level.getBlockState(wall));
       fixtureSetBlock(level,originalBlocks,wall,Blocks.OBSIDIAN.defaultBlockState());
      }
     for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++) {
      BlockPos roof=cageCenter.offset(dx,3,dz);
      cageOriginal.putIfAbsent(roof,level.getBlockState(roof));
      fixtureSetBlock(level,originalBlocks,roof,Blocks.OBSIDIAN.defaultBlockState());
     }
     guideDonkey.getNavigation().stop();
     var cagedPosition=guideDonkey.position();
     guideState.nextDonkeyFollowAt=0;
     Caravan.guideVisitingDonkeys(level,guideState,guideTrader,Math.max(22L,level.getGameTime()+21L));
     check(guideState.departurePathReady&&guideDonkey.position().equals(cagedPosition)
      &&!guideDonkey.getNavigation().isInProgress(),
      "a blocked visit route stops safely without prematurely pausing the later departure gate");checks++;
     guideState.nextDonkeyFollowAt=0;
     Caravan.guideDepartingDonkeys(level,guideState,guideTrader,Math.max(22L,level.getGameTime()+21L));
     check(!guideState.departurePathReady&&!Caravan.packAnimalsCanKeepUp(level,guideState,guideTrader)
      &&!Caravan.packAnimalsCanResume(level,guideState,guideTrader),
      "a fully enclosed live pack animal with no physical path pauses caravan departure");checks++;
     check(guideDonkey.position().equals(cagedPosition)&&cargoSlot.get().is(net.minecraft.world.item.Items.OAK_PLANKS)
      &&cargoSlot.get().getCount()==7&&guideState.routeDimension.equals(routeDimension)
      &&guideState.routeBell.equals(routeBell)&&guideState.routeRelationDelta==6&&guideState.routeVisitDay==19L
      &&guideState.roadKey.equals(routeDimension+routeBell.asLong())&&guideState.roadCursor==3,
      "a paused donkey keeps its real cargo and the caravan's exact destination and pending relationship update");checks++;
     guideState.traded=true;
     guideState.leaveAt=level.getGameTime();
     guideState.nextDonkeyFollowAt=0;
     boolean orphanedDepartureCompleted=Caravan.continueDepartureWithoutVillage(level,guideState,guideTrader);
     check(!orphanedDepartureCompleted&&guideState.llamas.contains(guideDonkey.getUUID())
      &&guideDonkey.isAlive()&&guideDonkey.getLeashHolder()==guideTrader
      &&cargoSlot.get().is(net.minecraft.world.item.Items.OAK_PLANKS)&&cargoSlot.get().getCount()==7
      &&guideState.waitingForDonkeys,
      "a missing village cannot complete a blocked departure or discard its registered, cargo-carrying donkey");checks++;
     check(!Caravan.cleanupForChecks(level,guideState)&&guideDonkey.isAlive()
      &&guideState.llamas.contains(guideDonkey.getUUID())&&cargoSlot.get().getCount()==7,
      "generic cleanup also refuses to discard a due-departure donkey that is neither home nor safely released");checks++;
     guideState.traded=false;
     guideState.leaveAt=Long.MAX_VALUE;
    } finally {
     for(var entry:cageOriginal.entrySet())fixtureSetBlock(level,originalBlocks,entry.getKey(),entry.getValue());
    }
    // Exercise an outbound departure, not the saved-spawn home-arrival shortcut.
    guideTrader.teleportTo(guideAt.getX()-5.5,guideAt.getY(),guideAt.getZ()+.5);
    guideTrader.setOnGround(true);
    guideState.nextDonkeyFollowAt=0;
    BlockPos retryApproach=Caravan.safePackDonkeyApproach(level,guideDonkey,guideTrader);
    double donkeySide=(retryApproach==null?0.0:(retryApproach.getX()+.5-guideTrader.getX())*(guideDonkey.getX()-guideTrader.getX())
     +(retryApproach.getZ()+.5-guideTrader.getZ())*(guideDonkey.getZ()-guideTrader.getZ()));
    check(retryApproach!=null&&donkeySide>0.0,"distant pack donkey gets an approach on its side of the moving trader [goal="
     +retryApproach+", sideProjection="+donkeySide+"]");checks++;
    long routeRetryAt=Math.max(43L,level.getGameTime()+42L);
    Caravan.guideDepartingDonkeys(level,guideState,guideTrader,routeRetryAt);
    boolean retryPathStarted=guideState.departurePathReady&&guideDonkey.getNavigation().isInProgress();
    for(int step=0;step<240&&!Caravan.packAnimalsCanKeepUp(level,guideState,guideTrader);step++) {
     guideDonkey.tick();
     if((step+1)%20==0) Caravan.guideDepartingDonkeys(level,guideState,guideTrader,routeRetryAt+step+1L);
    }
    var retryNavigation=guideDonkey.getNavigation();
    var retryPath=retryNavigation.getPath();
    BlockPos retryTarget=retryNavigation.getTargetPos();
    double retryDistance=guideDonkey.distanceToSqr(guideTrader);
    String retryPathFailure=retryPath==null?"none":Caravan.donkeyPathFailureReason(level,guideDonkey,retryPath,retryTarget);
    check(retryPathStarted&&guideState.departurePathReady&&Caravan.packAnimalsCanKeepUp(level,guideState,guideTrader)
     &&retryDistance<=16.0,
     "a blocked donkey route is retried, and departure resumes only after the real path brings the donkey back in range"
      + " [retryStarted="+retryPathStarted+", pathReady="+guideState.departurePathReady
      +", canKeepUp="+Caravan.packAnimalsCanKeepUp(level,guideState,guideTrader)+", distanceSq="+retryDistance
      +", donkey="+guideDonkey.position()+", trader="+guideTrader.position()+", leash="
      +(guideDonkey.getLeashHolder()==null?"none":guideDonkey.getLeashHolder().getUUID())
      +", navInProgress="+retryNavigation.isInProgress()+", target="+retryTarget
      +", pathCanReach="+(retryPath!=null&&retryPath.canReach())+", pathFailure="+retryPathFailure
      +", pathNodes="+(retryPath==null?"none":retryPath.getNodeCount())+"]");checks++;
   guideTrader.teleportTo(guideAt.getX()+.5,guideAt.getY(),guideAt.getZ()+.5);
   guideTrader.setOnGround(true);
   guideTrader.getNavigation().stop();
   guideDonkey.teleportTo(guideAt.getX()+20.5,guideAt.getY(),guideAt.getZ()+.5);
   check(!Caravan.packAnimalsCanKeepUp(level,guideState,guideTrader)&&!Caravan.packAnimalsCanResume(level,guideState,guideTrader),
    "departure pauses when a pack donkey exceeds the loaded follow range");checks++;
   guideTrader.setOnGround(true);
   BlockPos traderPathGoal=guideAt.west(6);
   var traderPath=guideTrader.getNavigation().createPath(traderPathGoal,1);
   boolean traderPathReachable=traderPath!=null&&traderPath.canReach();
   boolean traderRouteAccepted=guideTrader.getNavigation().moveTo(traderPath,.5);
   boolean traderWasMoving=traderRouteAccepted&&!guideTrader.getNavigation().isDone();
   var heldAt=Caravan.holdForPackAnimals(level,guideTrader,true);
   boolean waitSpotSafe=heldAt!=null&&level.hasChunkAt(heldAt)&&CaravanSites.canStand(level,guideTrader,heldAt,java.util.List.of());
   boolean holdApplied=heldAt!=null&& (heldAt.equals(guideTrader.blockPosition())
    ? guideTrader.getNavigation().isDone()
    : !guideTrader.getNavigation().isDone()&&heldAt.equals(guideTrader.getNavigation().getTargetPos()));
   check(traderPathReachable&&traderWasMoving&&waitSpotSafe&&holdApplied,
    "merchant stops at or navigates to its safe loaded wait spot while a pack donkey catches up [reachable="+traderPathReachable
     +", accepted="+traderRouteAccepted+", wait="+heldAt+", merchant="+guideTrader.blockPosition()
     +", waitSafe="+waitSpotSafe+", target="+guideTrader.getNavigation().getTargetPos()
     +", stopped="+guideTrader.getNavigation().isDone()+"]");checks++;
   guideDonkey.teleportTo(guideTrader.getX()+2.5,guideTrader.getY(),guideTrader.getZ());
   double resumeDistance=guideDonkey.distanceToSqr(guideTrader);
   check(resumeDistance<=Caravan.DONKEY_FOLLOW_DISTANCE_SQR&&Caravan.packAnimalsCanResume(level,guideState,guideTrader),
    "departure resumes only after the pack donkey physically catches up [distanceSq="+resumeDistance+"]");checks++;
   guideDonkey.teleportTo(guideAt.getX()+8.5,guideAt.getY(),guideAt.getZ()+.5);
   check(!Caravan.packAnimalsAtSpawn(level,guideState),"departure cannot complete while the loaded donkey is far from the origin");checks++;
   // Block +2 is an allowed safe arrival cell (minimum horizontal distance 2, maximum 4).
   // Keep this stalled-donkey case outside that production tolerance instead.
   guideDonkey.teleportTo(guideAt.getX()+5.5,guideAt.getY(),guideAt.getZ()+.5);
   check(!Caravan.packAnimalsAtSpawn(level,guideState),
    "a donkey stalled five blocks from the origin is not mistaken for an arrived pack animal");checks++;
   guideDonkey.getNavigation().stop();
   guideTrader.getNavigation().stop();
   guideTrader.teleportTo(guideAt.getX()+.5,guideAt.getY(),guideAt.getZ()+.5);
   guideState.nextDonkeyFollowAt=0;
   Caravan.guideDepartingDonkeys(level,guideState,guideTrader,Math.max(45L,level.getGameTime()+44L));
   BlockPos homeApproach=guideDonkey.getNavigation().getTargetPos();
   long homeApproachHorizontal=homeApproach==null?0L:
    (long)(homeApproach.getX()-guideAt.getX())*(homeApproach.getX()-guideAt.getX())
     +(long)(homeApproach.getZ()-guideAt.getZ())*(homeApproach.getZ()-guideAt.getZ());
   check(guideState.departurePathReady&&homeApproach!=null&&homeApproachHorizontal>=9L
    &&homeApproach.distSqr(guideAt)<=Caravan.PACK_HOME_APPROACH_DISTANCE_SQR
    &&guideDonkey.getNavigation().isInProgress(),
    "a donkey outside the arrival ring receives a real path to a safe three-to-four-block home approach [target="+homeApproach
     +", path="+guideDonkey.getNavigation().isInProgress()+", ready="+guideState.departurePathReady+"]");checks++;
   BlockPos diagonalArrival=guideAt.offset(2,0,1);
   guideDonkey.teleportTo(diagonalArrival.getX()+.5,diagonalArrival.getY(),diagonalArrival.getZ()+.5);
   BlockPos arrivalFeet=guideDonkey.blockPosition();
   long arrivalHorizontal=(long)(arrivalFeet.getX()-guideAt.getX())*(arrivalFeet.getX()-guideAt.getX())
    +(long)(arrivalFeet.getZ()-guideAt.getZ())*(arrivalFeet.getZ()-guideAt.getZ());
   check(arrivalHorizontal>=4L&&arrivalFeet.distSqr(guideAt)<=Caravan.PACK_HOME_APPROACH_DISTANCE_SQR
    &&CaravanSites.canStand(level,guideDonkey,arrivalFeet,java.util.List.of(guideTrader.getBoundingBox().inflate(.25)))
    &&Caravan.packAnimalsAtSpawn(level,guideState),
    "a donkey in a collision-free 2-by-1 cell inside the 2-to-4-block arrival ring counts as arrived");checks++;
   guideDonkey.getNavigation().stop();
   var unloaded=java.util.UUID.randomUUID();guideState.llamas.add(unloaded);
   check(!level.hasChunkAt(far)&&!Caravan.packAnimalsAtSpawn(level,guideState)&&!Caravan.packAnimalsCanKeepUp(level,guideState,guideTrader)
    &&!level.hasChunkAt(far),"an unloaded member keeps departure open without loading its chunk");checks++;
   guideState.llamas.remove(unloaded);
   guideDonkey.teleportTo(guideAt.getX()+3.5,guideAt.getY(),guideAt.getZ()+.5);
   check(Caravan.packAnimalsAtSpawn(level,guideState),"arrival gate opens when the loaded pack donkey reaches a safe three-block home cell");checks++;
   guideState.llamas.add(guideDonkey.getUUID());
   guideDonkey.addTag("vp_caravan");
   guideDonkey.addTag("vp_caravan_bell:"+guideState.bell.asLong());
   guideDonkey.removeLeash();
   guideDonkey.setLeashedTo(externalHolder,true);
     guideDonkey.teleportTo(before.x,before.y,before.z);
     guideDonkey.getNavigation().stop();
   guideState.nextDonkeyFollowAt=0;
   Caravan.guideDepartingDonkeys(level,guideState,guideTrader,Math.max(2L,level.getGameTime()+1));
   check(guideDonkey.getLeashHolder()==externalHolder&&!guideState.llamas.contains(guideDonkey.getUUID())
    &&cargoSlot.get().is(net.minecraft.world.item.Items.OAK_PLANKS)&&cargoSlot.get().getCount()==7,
    "a separately held donkey is released from caravan state without stealing its lead or losing real cargo");checks++;
   check(Caravan.packAnimalsAtSpawn(level,guideState),"a donkey released to another holder cannot permanently block caravan departure");checks++;
   guideDonkey.removeLeash();
   guideDonkey.addTag("vp_caravan");
   guideDonkey.addTag("vp_caravan_bell:"+guideState.bell.asLong());
   guideState.llamas.add(guideDonkey.getUUID());
   var deathArea=guideDonkey.getBoundingBox().inflate(2);
   var oldCargoDrops=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,deathArea,
    item->item.getItem().is(net.minecraft.world.item.Items.OAK_PLANKS)).stream().map(net.minecraft.world.entity.Entity::getUUID).collect(java.util.stream.Collectors.toSet());
   boolean killed=guideDonkey.hurtServer(level,level.damageSources().generic(),1000.0F);
   var deathDrops=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,deathArea,
    item->item.getItem().is(net.minecraft.world.item.Items.OAK_PLANKS)&&!oldCargoDrops.contains(item.getUUID()));
   check(!guideState.llamas.contains(guideDonkey.getUUID())&&cargoSlot.get().isEmpty()
    &&!guideDonkey.isAlive()&&killed&&deathDrops.size()==1
    &&deathDrops.stream().mapToInt(item->item.getItem().getCount()).sum()==7,
    "AFTER_DEATH clears vanilla-dropped cargo exactly once before releasing membership [killed="+killed
     +", alive="+guideDonkey.isAlive()+", members="+guideState.llamas.contains(guideDonkey.getUUID())
     +", slot="+cargoSlot.get().getCount()+", drops="+deathDrops.stream().map(item->item.getItem().toString()).toList()+"]");checks++;
   for(var item:deathDrops)item.discard();
   var cleanupCargoDonkey=EntityTypes.DONKEY.spawn(level,guideAt.east(3),EntitySpawnReason.COMMAND);
   var cleanupEmptyDonkey=EntityTypes.DONKEY.spawn(level,guideAt.east(4),EntitySpawnReason.COMMAND);
   if(cleanupCargoDonkey==null||cleanupEmptyDonkey==null)throw new IllegalStateException("Caravan site: cannot spawn arrival cleanup donkeys");
   try {
    Caravan.prepareDonkeyCargo(cleanupCargoDonkey,2,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS,7));
    var cleanupChest=cleanupCargoDonkey.getSlot(499);
    int cleanupCargoSlot=-1;
    for(int slot=2;slot<17;slot++) {
     var access=cleanupCargoDonkey.getSlot(500+slot);
     if(access!=null&&access.get().is(net.minecraft.world.item.Items.OAK_PLANKS)&&access.get().getCount()==7) {
      cleanupCargoSlot=slot;
      break;
     }
    }
    if(cleanupChest==null||!cleanupCargoDonkey.hasChest()||!cleanupChest.get().is(net.minecraft.world.item.Items.CHEST)||cleanupCargoSlot<0)
     throw new IllegalStateException("Caravan site: cannot equip arrival cleanup donkey cargo [hasChest="+cleanupCargoDonkey.hasChest()
      +", chestSlot="+(cleanupChest==null?"null":cleanupChest.get())+", cargoSlot="+cleanupCargoSlot+"]");
    var cleanupCargo=cleanupCargoDonkey.getSlot(500+cleanupCargoSlot);
    cleanupCargoDonkey.addTag("vp_caravan");
    cleanupCargoDonkey.addTag("vp_caravan_bell:"+guideState.bell.asLong());
    cleanupCargoDonkey.setLeashedTo(externalHolder,true);
    cleanupEmptyDonkey.addTag("vp_caravan");
    cleanupEmptyDonkey.addTag("vp_caravan_bell:"+guideState.bell.asLong());
    cleanupEmptyDonkey.setLeashedTo(externalHolder,true);
    var cleanupState=new Caravan.State(guideTrader,level,ref,at,-1);
    cleanupState.traded=true;
    cleanupState.leaveAt=level.getGameTime()-1L;
    cleanupState.llamas.add(cleanupCargoDonkey.getUUID());
    cleanupState.llamas.add(cleanupEmptyDonkey.getUUID());
    boolean cleaned=Caravan.cleanupForChecks(level,cleanupState);
    check(cleaned&&cleanupCargoDonkey.isAlive()&&cleanupCargoDonkey.getLeashHolder()==externalHolder
     &&cleanupCargo.get().is(net.minecraft.world.item.Items.OAK_PLANKS)&&cleanupCargo.get().getCount()==7
     &&!cleanupCargoDonkey.entityTags().contains("vp_caravan")
     &&!cleanupCargoDonkey.entityTags().contains("vp_caravan_bell:"+guideState.bell.asLong())
     &&!cleanupState.llamas.contains(cleanupCargoDonkey.getUUID()),
     "arrival cleanup releases cargo donkey membership while retaining its real cargo and the player's lead");checks++;
    check(cleaned&&cleanupEmptyDonkey.isAlive()&&cleanupEmptyDonkey.getLeashHolder()==externalHolder
     &&!cleanupEmptyDonkey.entityTags().contains("vp_caravan")
     &&!cleanupState.llamas.contains(cleanupEmptyDonkey.getUUID()),
     "arrival cleanup preserves an externally leashed empty donkey instead of discarding it");checks++;
    cleanupCargo.set(net.minecraft.world.item.ItemStack.EMPTY);
    Caravan.syncCargoVisual(level,cleanupCargoDonkey);
   } finally {
    cleanupCargoDonkey.discard();
    cleanupEmptyDonkey.discard();
   }
  } finally {
   if(guideTrader!=null)Caravan.removeActiveStateForChecks(guideTrader.getUUID());
   if(guideDonkey!=null)guideDonkey.discard();
   if(guideTrader!=null)guideTrader.discard();
   if(externalHolder!=null)externalHolder.discard();
   for(var entry:guideClearanceOriginal.entrySet())fixtureSetBlock(level,originalBlocks,entry.getKey(),entry.getValue());
   for(var entry:guideFloorOriginal.entrySet())fixtureSetBlock(level,originalBlocks,entry.getKey(),entry.getValue());
  }
  var area=new net.minecraft.world.phys.AABB(at).inflate(110);
  try {
   check(Caravan.spawn(level,ref),"public caravan spawn succeeds with room for the complete group");checks++;
   var members=level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,area,e->e.entityTags().contains("vp_caravan"));
   check(members.stream().filter(de.villagerplus.entity.CaravanTraderEntity.class::isInstance).count()==1
    &&members.stream().noneMatch(e->e.getType()==net.minecraft.world.entity.EntityTypes.WANDERING_TRADER)
    &&members.stream().filter(net.minecraft.world.entity.animal.equine.Donkey.class::isInstance).count()==2
    &&members.stream().filter(net.minecraft.world.entity.animal.golem.IronGolem.class::isInstance).count()==1,"production spawn creates one trader, two donkeys and one guard");checks++;
   var merchant=members.stream().filter(de.villagerplus.entity.CaravanTraderEntity.class::isInstance)
    .map(de.villagerplus.entity.CaravanTraderEntity.class::cast).findFirst().orElseThrow();
   check(Caravan.hasSafeTraderStand(level,merchant,merchant.blockPosition()),
    "production caravan starts on loaded collision-free ground without block entities under or inside the trader");checks++;
   var active=Caravan.activeStateForChecks(merchant.getUUID());
   var registeredDonkeys=members.stream().filter(net.minecraft.world.entity.animal.equine.Donkey.class::isInstance)
    .map(net.minecraft.world.entity.animal.equine.Donkey.class::cast).toList();
   check(active!=null&&registeredDonkeys.size()==2&&registeredDonkeys.stream().allMatch(d->active.llamas.contains(d.getUUID())
    &&d.getLeashHolder()==merchant&&d.isPersistenceRequired()),"production trader registers persistent real pack donkeys on its lead");checks++;
   active.traded=true;
   active.leaveAt=active.born+1L;
   active.routeDimension=Network.dim(level);
   active.routeBell=at.west(80).immutable();
   active.routeRelationDelta=7;
   active.routeVisitDay=23L;
   active.roadKey=active.routeDimension+active.routeBell.asLong();
   active.roadCursor=3;
   Caravan.persistVisitState(level,active);
   var saved=merchant.visitData();
   check(saved!=null,"new merchant spawn attaches its visit snapshot");checks++;
   var encoded=de.villagerplus.entity.CaravanTraderEntity.VISIT_DATA_CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE,saved).getOrThrow();
   var decoded=de.villagerplus.entity.CaravanTraderEntity.VISIT_DATA_CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,encoded).getOrThrow();
   check(saved.equals(decoded)&&saved.travel().members().size()==3,"active merchant persists its partner, route and complete caravan member ids");checks++;
   Caravan.removeActiveStateForChecks(merchant.getUUID());
   Caravan.restoreVisitState(level,merchant,decoded);
   var restoredDeparture=Caravan.activeStateForChecks(merchant.getUUID());
   check(restoredDeparture!=null&&restoredDeparture.traded&&restoredDeparture.leaveAt==active.leaveAt
    &&registeredDonkeys.stream().allMatch(d->restoredDeparture.llamas.contains(d.getUUID())&&d.getLeashHolder()==merchant)
    &&restoredDeparture.routeBell.equals(active.routeBell)&&restoredDeparture.routeVisitDay==active.routeVisitDay
    &&restoredDeparture.roadCursor==active.roadCursor
    &&!Caravan.shouldExpireWithoutCargo(restoredDeparture,restoredDeparture.born+12001L,false),
    "a saved due departure restores its donkey membership, return route, and no-premature-timeout gate");checks++;
    check(merchant.getOffers().isEmpty()
     &&merchant.infoLines().stream().anyMatch(line->line.startsWith("Handelspartner|"+merchant.partnerName()+"|"))
     &&merchant.infoLines().stream().anyMatch(line->line.startsWith("Spezialität|"))
     &&merchant.infoLines().stream().noneMatch(line->line.startsWith("Angebot|")),
     "independent merchant exposes its profile but withholds offers until real cargo backs them");checks++;
   var tradeFixture=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.create(level,EntitySpawnReason.COMMAND);
   if(tradeFixture==null)throw new IllegalStateException("Caravan site: could not create merchant stock fixture");
   try {
    var offeredCargo=new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS,8);
    tradeFixture.installBackedTradeOffers(List.of(offeredCargo));
    var installedStock=tradeFixture.backedTradeStockSnapshot();
    var installedOffer=tradeFixture.getOffers().get(0);
    check(tradeFixture.getMainHandItem().is(net.minecraft.world.item.Items.OAK_PLANKS)
     &&tradeFixture.getMainHandItem().getCount()==1&&offeredCargo.getCount()==8
     &&installedStock.size()==1&&installedStock.get(0).is(net.minecraft.world.item.Items.OAK_PLANKS)
     &&installedStock.get(0).getCount()==8&&installedOffer.getResult().getCount()==8,
     "merchant hand displays one copied trade item after offer installation without changing reserved stock or offer quantity");checks++;

    var tradeSave=net.minecraft.world.level.storage.TagValueOutput.createWithContext(
     net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
    tradeFixture.saveWithoutId(tradeSave);
    var loadedFixture=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.create(level,EntitySpawnReason.COMMAND);
    if(loadedFixture==null)throw new IllegalStateException("Caravan site: could not create merchant reload fixture");
    try {
     loadedFixture.load(net.minecraft.world.level.storage.TagValueInput.create(
      net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),tradeSave.buildResult()));
     var loadedStock=loadedFixture.backedTradeStockSnapshot();
     check(loadedFixture.getMainHandItem().is(net.minecraft.world.item.Items.OAK_PLANKS)
      &&loadedFixture.getMainHandItem().getCount()==1&&loadedStock.size()==1
      &&loadedStock.get(0).is(net.minecraft.world.item.Items.OAK_PLANKS)&&loadedStock.get(0).getCount()==8
      &&loadedFixture.getOffers().size()==1&&loadedFixture.getOffers().get(0).getResult().getCount()==8,
      "merchant hand is rebuilt from saved backing stock while the saved offer and stock remain unchanged");checks++;
     loadedFixture.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
      new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.OAK_PLANKS));
     var emptySave=net.minecraft.world.level.storage.TagValueOutput.createWithContext(
      net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess());
     var emptyFixture=de.villagerplus.entity.ModEntities.CARAVAN_TRADER.create(level,EntitySpawnReason.COMMAND);
     if(emptyFixture==null)throw new IllegalStateException("Caravan site: could not create empty merchant reload fixture");
     try {
      emptyFixture.saveWithoutId(emptySave);
      loadedFixture.load(net.minecraft.world.level.storage.TagValueInput.create(
       net.minecraft.util.ProblemReporter.DISCARDING,level.registryAccess(),emptySave.buildResult()));
      check(loadedFixture.getMainHandItem().isEmpty()&&loadedFixture.backedTradeStockSnapshot().isEmpty(),
       "loading an empty saved backing stock clears any stale displayed hand copy");checks++;
     } finally {emptyFixture.discard();}
    } finally {loadedFixture.discard();}

    tradeFixture.notifyTrade(installedOffer);
    var paidStock=tradeFixture.backedTradeStockSnapshot();
    check(tradeFixture.getMainHandItem().is(net.minecraft.world.item.Items.EMERALD)
     &&tradeFixture.getMainHandItem().getCount()==1&&paidStock.size()==1
     &&paidStock.get(0).is(net.minecraft.world.item.Items.EMERALD)&&paidStock.get(0).getCount()==4
     &&installedOffer.getUses()==1&&installedOffer.getResult().getCount()==8,
     "after a sale the displayed item follows accepted emerald payment without changing the merchant offer");checks++;

    var stockBeforeDisplayRead=tradeFixture.backedTradeStockSnapshot();
    int usesBeforeDisplayRead=installedOffer.getUses();
    var displayRead=tradeFixture.getMainHandItem().copy();
    var stockAfterDisplayRead=tradeFixture.backedTradeStockSnapshot();
    check(displayRead.is(net.minecraft.world.item.Items.EMERALD)&&displayRead.getCount()==1
     &&stockBeforeDisplayRead.size()==stockAfterDisplayRead.size()
     &&stockBeforeDisplayRead.get(0).getCount()==stockAfterDisplayRead.get(0).getCount()
     &&installedOffer.getUses()==usesBeforeDisplayRead,
     "reading the display copy leaves physical backing stock and MerchantOffer accounting unchanged");checks++;

    check(tradeFixture.consumeBackedTradeStock(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.EMERALD,4))
     &&tradeFixture.backedTradeStockSnapshot().isEmpty()&&tradeFixture.getMainHandItem().isEmpty(),
     "consuming the final backing item clears the merchant hand");checks++;
   } finally {tradeFixture.discard();}
   check(members.stream().allMatch(e->CaravanSites.canStand(level,e,e.blockPosition(),List.of())),"every production companion has supported full body clearance");checks++;
  } finally {
   var remaining=level.getEntitiesOfClass(de.villagerplus.entity.CaravanTraderEntity.class,area,e->true);
   for(var merchant:remaining)Caravan.removeActiveStateForChecks(merchant.getUUID());
   for(var member:level.getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,area,e->e.entityTags().contains("vp_caravan")))member.discard();
  }
  return checks;
  } finally {
   for(var cell:originalBlocks.values())cell.restore(level);
  }
 }
 private static void fixtureSetBlock(ServerLevel level,java.util.Map<BlockPos,BlockSnapshot> snapshots,
  BlockPos pos,net.minecraft.world.level.block.state.BlockState state) {
  BlockPos key=pos.immutable();
  snapshots.computeIfAbsent(key,ignored->BlockSnapshot.capture(level,key));
  level.setBlock(pos,state,3);
 }
 private static BlockPos findGuideFixtureArea(ServerLevel level,BlockPos origin) {
  for(int offset:new int[]{512,1024,1536,2048}) {
   BlockPos candidate=origin.offset(offset,0,offset);
   boolean occupied=false;
   for(int x=-16;x<=16&&!occupied;x++)for(int z=-16;z<=16&&!occupied;z++)
    for(int y=-1;y<=3;y++) {
     BlockPos pos=candidate.offset(x,y,z);
     level.getChunkAt(pos);
     if(level.getBlockEntity(pos)!=null) {occupied=true;break;}
    }
   if(occupied)continue;
   var bounds=new net.minecraft.world.phys.AABB(candidate.getX()-16,candidate.getY()-1,candidate.getZ()-16,
    candidate.getX()+17,candidate.getY()+4,candidate.getZ()+17);
   if(level.getEntitiesOfClass(net.minecraft.world.entity.Entity.class,bounds,net.minecraft.world.entity.Entity::isAlive).isEmpty())
    return candidate;
  }
  throw new IllegalStateException("Caravan site: no isolated guide route fixture area available");
 }
 private record BlockSnapshot(BlockPos position,net.minecraft.world.level.block.state.BlockState state,
  net.minecraft.nbt.CompoundTag blockEntity) {
  private static BlockSnapshot capture(ServerLevel level,BlockPos position) {
   var entity=level.getBlockEntity(position);
   return new BlockSnapshot(position.immutable(),level.getBlockState(position),
    entity==null?null:entity.saveWithFullMetadata(level.registryAccess()));
  }
  private void restore(ServerLevel level) {
   level.removeBlockEntity(position);
   level.setBlock(position,state,3);
   if(blockEntity!=null) {
    var restored=net.minecraft.world.level.block.entity.BlockEntity.loadStatic(
     position,state,blockEntity.copy(),level.registryAccess());
    if(restored!=null)level.setBlockEntity(restored);
   }
  }
 }
 private static void check(boolean value,String message){if(!value)throw new IllegalStateException("Caravan site: "+message);}
}
