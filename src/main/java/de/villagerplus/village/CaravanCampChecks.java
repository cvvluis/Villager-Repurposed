package de.villagerplus.village;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;

/** Persistence codec checks for crash-safe caravan camp cleanup records. */
public final class CaravanCampChecks {
 private CaravanCampChecks() { }

 public static int run() {
  int checks=0;
  check(CaravanCampData.empty().isEmpty(),"missing camp attachment has an empty safe default");checks++;
  var empty=CaravanCampData.CODEC.parse(JsonOps.INSTANCE,new com.google.gson.JsonArray()).getOrThrow();
  check(empty.isEmpty(),"empty persisted list decodes cleanly");checks++;
  var legacy=CaravanCampData.CampBlock.CODEC.parse(JsonOps.INSTANCE,new JsonObject()).getOrThrow();
  check(legacy.pos().equals(BlockPos.ZERO)&&legacy.expected().is(Blocks.AIR),"older partial records decode with safe position/state defaults");checks++;
  var encoded=CaravanCampData.CampBlock.CODEC.encodeStart(JsonOps.INSTANCE,
   new CaravanCampData.CampBlock(new BlockPos(12,70,-4),Blocks.SPRUCE_FENCE.defaultBlockState()));
  check(encoded.result().isPresent(),"camp position and expected block state encode to persistent data");checks++;
  check(!Caravan.unlitCampfireState().getValue(CampfireBlock.LIT),"a new camp fire starts unlit until real village fuel is available");checks++;
  var fireRecord=CaravanCampData.CampBlock.CODEC.encodeStart(JsonOps.INSTANCE,
   new CaravanCampData.CampBlock(new BlockPos(14,70,-4),Caravan.unlitCampfireState())).getOrThrow();
  var restoredFire=CaravanCampData.CampBlock.CODEC.parse(JsonOps.INSTANCE,fireRecord).getOrThrow();
  check(restoredFire.expected().equals(Caravan.unlitCampfireState()),"the persisted camp manifest keeps the fire's unfunded state for safe retry and cleanup");checks++;
  BlockPos campAnchor=new BlockPos(-41,65,20),caravanTarget=campAnchor.east(36);
  List<BlockPos> campCells=List.of(campAnchor.offset(-3,0,-2),campAnchor.offset(3,0,2),campAnchor.offset(0,3,0),campAnchor.offset(-1,0,0));
  BlockPos guardPost=campAnchor.north(3);
  check(Caravan.guardTarget(campCells,true,false,caravanTarget).equals(guardPost)
   &&Caravan.guardTarget(campCells,true,true,caravanTarget).equals(caravanTarget)
   &&Caravan.guardTarget(List.of(),true,false,caravanTarget).equals(caravanTarget),
   "the camp guard holds the camp during village service, rejoins the departing caravan, and safely falls back without a camp");checks++;
  check(Caravan.shouldReconcileArmedGuardVisuals(40L,true,false)
   &&!Caravan.shouldReconcileArmedGuardVisuals(41L,true,false)
   &&!Caravan.shouldReconcileArmedGuardVisuals(20L,false,false)
   &&Caravan.shouldReconcileArmedGuardVisuals(1200L,false,false)
   &&!Caravan.shouldReconcileArmedGuardVisuals(1180L,false,false)
   &&Caravan.shouldReconcileArmedGuardVisuals(20L,false,true),
   "armed-guard visuals reconcile every 20 ticks for active camps, once after startup, and periodically to remove orphaned saves");checks++;
  check(!Caravan.shouldRunCargoStartupScan(119L,120L)
   &&Caravan.shouldRunCargoStartupScan(120L,120L)
   &&!Caravan.shouldRunCargoStartupScan(2400L,null),
   "cargo visuals receive one delayed startup scan; later-loaded donkeys use entity-load reconciliation");checks++;
  check(CaravanSites.armedGuardSpot(guardPost).equals(guardPost.east()),
   "the visible sword-bearing villager uses the separately validated cell beside the combat post");checks++;
  BlockPos campLayoutCenter=new BlockPos(18,70,-9);
  Map<BlockPos,BlockState> layout=Caravan.campLayout(campLayoutCenter);
  boolean stripedCanopy=true;
  for(int x=-2;x<=2;x++)for(int z=-1;z<=1;z++) {
	   BlockState expected=((x+z)&1)==0?Blocks.WOOL.red().defaultBlockState():Blocks.WOOL.white().defaultBlockState();
   stripedCanopy&=expected.equals(layout.get(campLayoutCenter.offset(x,3,z)));
  }
  long fencePosts=layout.values().stream().filter(state->state.is(Blocks.SPRUCE_FENCE)).count();
  boolean tentFrame=true;
  for(int x:new int[]{-2,2})for(int z:new int[]{-1,1})for(int y=0;y<3;y++)
   tentFrame&=layout.get(campLayoutCenter.offset(x,y,z))!=null&&layout.get(campLayoutCenter.offset(x,y,z)).is(Blocks.SPRUCE_FENCE);
	  check(stripedCanopy&&tentFrame&&fencePosts==18&&layout.get(campLayoutCenter.above(4)).is(Blocks.WOOL.red()),
   "the temporary camp has a raised tent frame and complete red-and-white canopy with a visible ridge");checks++;
  boolean hitching=true;
  for(int z:new int[]{-2,2}) {
   for(int y=0;y<3;y++) hitching&=layout.get(campLayoutCenter.offset(-3,y,z))!=null&&layout.get(campLayoutCenter.offset(-3,y,z)).is(Blocks.SPRUCE_FENCE);
   hitching&=layout.get(campLayoutCenter.offset(-2,0,z))!=null&&layout.get(campLayoutCenter.offset(-2,0,z)).is(Blocks.HAY_BLOCK);
  }
  hitching&=layout.get(campLayoutCenter.offset(-3,0,0))!=null&&layout.get(campLayoutCenter.offset(-3,0,0)).is(Blocks.BARREL);
  check(hitching,"two tall animal hitching posts have nearby feed bales and a separate supply barrel");checks++;
  boolean cooking=layout.get(campLayoutCenter.offset(3,0,0)).equals(Caravan.unlitCampfireState())
   &&layout.get(campLayoutCenter.offset(3,0,2)).is(Blocks.SMOKER)
   &&layout.get(campLayoutCenter.offset(2,0,2)).is(Blocks.CAULDRON)
   &&List.of(campLayoutCenter.offset(2,0,0),campLayoutCenter.offset(3,0,-1),campLayoutCenter.offset(3,0,1))
    .stream().allMatch(pos->layout.get(pos)!=null&&layout.get(pos).is(Blocks.COBBLESTONE));
  BlockPos campPost=CaravanCampSecurity.post(List.copyOf(layout.keySet()));
  check(cooking&&campPost.equals(campLayoutCenter.north(3))&&!layout.containsKey(campLayoutCenter.south(2))
   &&!layout.containsKey(campPost)&&!layout.containsKey(campPost.east()),
   "the cobble cooking corner is separate from the open tent entrance and both validated guard cells");checks++;
  BlockPos blockedSite=campAnchor, fallbackSite=campAnchor.east(12);
  check(Caravan.firstUsableCampSite(List.of(blockedSite,fallbackSite),candidate->candidate.equals(fallbackSite)).equals(fallbackSite)
   &&Caravan.firstUsableCampSite(List.of(blockedSite,fallbackSite),candidate->false)==null,
   "an unusable camp candidate falls through in order to the next complete site, or retries when all are blocked");checks++;
  return checks;
 }

 /** World-backed idempotence and ownership checks; caller supplies a loaded isolated fixture area. */
 public static int runWorld(ServerLevel level,BlockPos at) {
  int checks=0;
  BlockPos exact=at.offset(2,0,0), modified=at.offset(4,0,0), fire=at.offset(6,0,0), barrel=at.offset(8,0,0), namedBarrel=at.offset(10,0,0), paidFire=at.offset(14,0,0);
  BlockPos connected=at.offset(12,0,0), neighbor=connected.east();
  Map<BlockPos,Cell> before=new LinkedHashMap<>();
  for(BlockPos pos:List.of(exact,modified,fire,barrel,namedBarrel,connected,neighbor,paidFire)) {
   level.getChunkAt(pos);BlockEntity be=level.getBlockEntity(pos);
   before.put(pos,new Cell(level.getBlockState(pos),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
  }
  BlockState fence=Blocks.SPRUCE_FENCE.defaultBlockState();
  try {
   level.setBlock(exact,fence,3);
   var removed=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(exact,fence)));
   check(removed.isEmpty()&&level.getBlockState(exact).isAir(),"matching camp block is removed");checks++;
   check(Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(exact,fence))).isEmpty(),"repeated cleanup is idempotent");checks++;

   level.setBlock(connected,fence,3);
   level.setBlock(neighbor,fence,3);
   check(level.getBlockState(connected).getValue(FenceBlock.EAST),"neighboring spruce fence derives a connection on the camp post");checks++;
   var connectedLeft=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(connected,fence)));
   check(connectedLeft.isEmpty()&&level.getBlockState(connected).isAir()
    &&level.getBlockState(neighbor).is(Blocks.SPRUCE_FENCE),
    "derived fence connections do not strand an owned camp post or remove its pre-existing neighbor");checks++;

   level.setBlock(modified,Blocks.STONE.defaultBlockState(),3);
   var modifiedLeft=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(modified,fence)));
   check(modifiedLeft.isEmpty()&&level.getBlockState(modified).is(Blocks.STONE),"a player-modified block is retained and relinquished");checks++;

   BlockState expectedFire=Blocks.CAMPFIRE.defaultBlockState();
   level.setBlock(fire,expectedFire.setValue(CampfireBlock.LIT,false),3);
   check(Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(fire,expectedFire))).isEmpty()
    &&level.getBlockState(fire).isAir(),"rain-extinguished campfire is still recognized as camp-owned");checks++;

   level.setBlock(barrel,Blocks.BARREL.defaultBlockState(),3);
   if(level.getBlockEntity(barrel) instanceof BarrelBlockEntity container) container.setItem(0,new ItemStack(Items.EMERALD));
   var barrelLeft=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(barrel,Blocks.BARREL.defaultBlockState())));
   check(barrelLeft.isEmpty()&&level.getBlockState(barrel).is(Blocks.BARREL)
    &&level.getBlockEntity(barrel) instanceof BarrelBlockEntity container&&!container.getItem(0).isEmpty(),"nonempty camp barrel and its real inventory are preserved");checks++;

   level.setBlock(namedBarrel,Blocks.BARREL.defaultBlockState(),3);
   ItemStack namedBarrelStack = new ItemStack(Items.BARREL);
   namedBarrelStack.set(DataComponents.CUSTOM_NAME,net.minecraft.network.chat.Component.literal("Persönliche Vorräte"));
   if(level.getBlockEntity(namedBarrel) instanceof BarrelBlockEntity container) container.applyComponentsFromItemStack(namedBarrelStack);
   var namedBarrelLeft=Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(namedBarrel,Blocks.BARREL.defaultBlockState())));
   check(namedBarrelLeft.isEmpty()&&level.getBlockState(namedBarrel).is(Blocks.BARREL)
    &&level.getBlockEntity(namedBarrel) instanceof BarrelBlockEntity container&&container.hasCustomName()
    &&container.getCustomName().getString().equals("Persönliche Vorräte"),
    "a player-renamed empty camp barrel and its name are preserved");checks++;

   BlockState unlitCampfire=Caravan.unlitCampfireState();
   level.setBlock(paidFire,unlitCampfire,3);
   SimpleContainer fuel=new SimpleContainer(2);
   check(!Caravan.tryIgniteCampfire(level,paidFire,List.of(fuel))
    &&!level.getBlockState(paidFire).getValue(CampfireBlock.LIT),"campfire waits safely when no real village fuel is available");checks++;
   fuel.setItem(0,new ItemStack(Items.COAL,2));
   check(Caravan.tryIgniteCampfire(level,paidFire,List.of(fuel))
    &&level.getBlockState(paidFire).getValue(CampfireBlock.LIT)&&fuel.getItem(0).getCount()==1,
    "one stored coal is consumed to light the campfire");checks++;
   check(Caravan.tryIgniteCampfire(level,paidFire,List.of(fuel))&&fuel.getItem(0).getCount()==1,
    "retrying an already lit campfire does not charge fuel twice");checks++;
   level.setBlock(paidFire,unlitCampfire,3);
   fuel.clearContent();
   check(!Caravan.tryIgniteCampfire(level,paidFire,List.of(fuel))
    &&!level.getBlockState(paidFire).getValue(CampfireBlock.LIT),"an extinguished campfire waits for the next real fuel delivery");checks++;
   fuel.setItem(1,new ItemStack(Items.CHARCOAL));
   check(Caravan.tryIgniteCampfire(level,paidFire,List.of(fuel))
    &&level.getBlockState(paidFire).getValue(CampfireBlock.LIT)&&fuel.getItem(1).isEmpty(),
    "a later charcoal delivery completes the saved campfire retry");checks++;
   check(Caravan.clearLoadedCampBlocks(level,List.of(new CaravanCampData.CampBlock(paidFire,unlitCampfire))).isEmpty()
    &&level.getBlockState(paidFire).isAir(),"cleanup recognizes and removes the lit campfire owned by the persisted plan");checks++;
  } finally {
   for(BlockPos pos:before.keySet()) level.removeBlockEntity(pos);
   for(BlockPos pos:before.keySet()) level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
   before.forEach((pos,cell)->level.setBlock(pos,cell.state(),3));
   before.forEach((pos,cell)->{
    if(cell.data()!=null) {
     BlockEntity be=BlockEntity.loadStatic(pos,cell.state(),cell.data().copy(),level.registryAccess());
     if(be!=null) level.setBlockEntity(be);
    }
   });
  }

  BlockPos far=new BlockPos(1100000,280,1100000);
  var pending=Caravan.clearLoadedCampBlocks(level,java.util.List.of(new CaravanCampData.CampBlock(far,fence)));
  check(!level.hasChunkAt(far)&&pending.size()==1&&pending.getFirst().pos().equals(far),"unloaded camp positions stay queued without chunk loading");checks++;
  checks+=blockedCampSiteFallback(level,at.offset(64,0,0));
  checks+=campManifestCleanup(level,at.offset(96,0,0));
  checks+=CaravanCampSecurityChecks.runWorld(level,at.offset(32,0,0));
  return checks;
 }

 private static int campManifestCleanup(ServerLevel level,BlockPos center) {
  Map<BlockPos,BlockState> layout=Caravan.campLayout(center);
  Map<BlockPos,Cell> before=new LinkedHashMap<>();
  for(BlockPos pos:layout.keySet()) {
   level.getChunkAt(pos);BlockEntity be=level.getBlockEntity(pos);
   before.put(pos,new Cell(level.getBlockState(pos),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
  }
  try {
   layout.keySet().forEach(pos->level.setBlock(pos,Blocks.AIR.defaultBlockState(),3));
   layout.forEach((pos,state)->level.setBlock(pos,state,3));
   BlockPos playerChanged=center.above(4);
   level.setBlock(playerChanged,Blocks.STONE.defaultBlockState(),3);
   List<CaravanCampData.CampBlock> manifest=layout.entrySet().stream()
    .map(entry->new CaravanCampData.CampBlock(entry.getKey(),entry.getValue())).toList();
   List<CaravanCampData.CampBlock> pending=Caravan.clearLoadedCampBlocks(level,manifest);
   boolean onlyOwnedRemoved=pending.isEmpty()&&level.getBlockState(playerChanged).is(Blocks.STONE)
    &&layout.keySet().stream().filter(pos->!pos.equals(playerChanged)).allMatch(pos->level.getBlockState(pos).isAir());
   check(onlyOwnedRemoved,"departure cleanup removes the complete unchanged tent/cooking/hitching manifest but keeps a player-changed camp block");
   return 1;
  } finally {
   for(BlockPos pos:before.keySet())level.removeBlockEntity(pos);
   for(BlockPos pos:before.keySet())level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
   before.forEach((pos,cell)->level.setBlock(pos,cell.state(),3));
   before.forEach((pos,cell)->{
    if(cell.data()!=null) {
     BlockEntity be=BlockEntity.loadStatic(pos,cell.state(),cell.data().copy(),level.registryAccess());
     if(be!=null)level.setBlockEntity(be);
    }
   });
  }
 }

 private static int blockedCampSiteFallback(ServerLevel level,BlockPos at) {
  BlockPos first=at.offset(0,1,0),second=at.offset(16,1,0);
  List<BlockPos> touched=new java.util.ArrayList<>();
  for(BlockPos center:List.of(first,second)) {
   for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)touched.add(center.offset(x,-1,z));
   for(int x=-2;x<=2;x++)touched.add(center.offset(x,-1,-4));
   touched.addAll(Caravan.campLayout(center).keySet());
  }
  Map<BlockPos,Cell> before=new LinkedHashMap<>();
  for(BlockPos pos:touched) {
   level.getChunkAt(pos);BlockEntity be=level.getBlockEntity(pos);
   before.putIfAbsent(pos,new Cell(level.getBlockState(pos),be==null?null:be.saveWithFullMetadata(level.registryAccess())));
  }
  try {
   for(BlockPos center:List.of(first,second)) {
    for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)level.setBlock(center.offset(x,-1,z),Blocks.STONE.defaultBlockState(),3);
    for(int x=-2;x<=2;x++)level.setBlock(center.offset(x,-1,-4),Blocks.STONE.defaultBlockState(),3);
    for(BlockPos pos:Caravan.campLayout(center).keySet())level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
   }
   BlockPos blocker=first.offset(0,3,0);
   level.setBlock(blocker,Blocks.STONE.defaultBlockState(),3);
   BlockPos chosen=Caravan.firstUsableCampSite(List.of(first,second),candidate->Caravan.campSiteUsable(level,candidate));
   check(!Caravan.campSiteUsable(level,first)&&Caravan.campSiteUsable(level,second)&&second.equals(chosen),
    "a blocked tent cell rejects the first pad and the real site selector falls back to the next safe camp site");
   return 1;
  } finally {
   for(BlockPos pos:before.keySet())level.removeBlockEntity(pos);
   for(BlockPos pos:before.keySet())level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
   before.forEach((pos,cell)->level.setBlock(pos,cell.state(),3));
   before.forEach((pos,cell)->{
    if(cell.data()!=null) {
     BlockEntity be=BlockEntity.loadStatic(pos,cell.state(),cell.data().copy(),level.registryAccess());
     if(be!=null)level.setBlockEntity(be);
    }
   });
  }
 }

 private static void check(boolean value,String message) {
  if(!value) throw new IllegalStateException("Caravan camp persistence: "+message);
 }
 private record Cell(BlockState state,CompoundTag data) { }
}
