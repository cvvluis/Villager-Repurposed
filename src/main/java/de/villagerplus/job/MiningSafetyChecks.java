package de.villagerplus.job;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Palette;
import de.villagerplus.village.Anchor;
import de.villagerplus.village.VillageData;
import net.minecraft.nbt.CompoundTag;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** Isolated world checks: collision-safe steps and deferred mine closure, with fixture cleanup. */
public final class MiningSafetyChecks {
    private MiningSafetyChecks() { }
    private record Cell(BlockState state, CompoundTag blockEntity) { }
    private record MineSealCost(List<BlockPos> paidTargets, List<BlockPos> existingFullTargets) { }
    private record FixtureLease(Set<ChunkPos> chunks) { }
    private record PendingPortalOccupant(UUID entity, BlockPos cell, long spawnedAt, int entityTickCount) { }
    public static final int PREPARING = -1;
    private static final int FIXTURE_TICKET_RADIUS = 2;
    private static final Map<ServerLevel, FixtureLease> FIXTURE_TICKETS = new WeakHashMap<>();
    private static final Map<ServerLevel, String> LAST_PREPARING_STATUS = new WeakHashMap<>();
    private static final Map<ServerLevel, PendingPortalOccupant> PENDING_PORTAL_OCCUPANTS = new WeakHashMap<>();

    public static int run(ServerLevel level, BlockPos e) {
        if (!prepareEntityTickingFixture(level, e)) return PREPARING;
        PendingPortalOccupant pendingPortalOccupant=PENDING_PORTAL_OCCUPANTS.get(level);
        if(pendingPortalOccupant!=null) {
            if(level.getGameTime()<=pendingPortalOccupant.spawnedAt()) return PREPARING;
            net.minecraft.world.entity.Entity ticked=level.getEntity(pendingPortalOccupant.entity());
            if(!(ticked instanceof Villager villager)||villager.tickCount<=pendingPortalOccupant.entityTickCount()) return PREPARING;
        }
        Map<BlockPos, Cell> before = new HashMap<>();
        Villager v = null;
        Villager portalOccupant = null;
        java.util.List<Villager> assignedMiners = new java.util.ArrayList<>();
        boolean keepPendingPortalOccupant=false;
        int checks = 0;
        try {
            for (int x = -8; x <= 12; x++) for (int z = -9; z <= 9; z++) {
                level.getChunkAt(e.offset(x, 0, z));
                for (int y = -5; y <= 6; y++) {
                    BlockPos fixtureCell = e.offset(x,y,z);
                    if (level.getBlockEntity(fixtureCell) != null || level.getBlockState(fixtureCell).hasBlockEntity()) {
                        throw new IllegalStateException("Mining fixture overlaps a protected block entity at " + fixtureCell.toShortString());
                    }
                    rememberAndClear(level, before, fixtureCell);
                }
            }
            BlockPos bellPos=e.offset(-7,0,-7),stockPos=e.offset(-6,0,-6);
            level.setBlock(bellPos.below(),Blocks.STONE.defaultBlockState(),2);
            level.setBlock(bellPos,Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING,Direction.NORTH),2);
            if(!(level.getBlockEntity(bellPos) instanceof BellBlockEntity bell)) throw new IllegalStateException("Mine safety village bell was not created");
            // Keep this remote fixture's stock isolated from saved extra depots; restore the bell BE in finally.
            VillageData data=new VillageData();
            bell.setAttached(VillagerPlus.VILLAGE,data);
            de.villagerplus.village.VillageRef ref=new de.villagerplus.village.VillageRef(bellPos,data,bell);
            level.setBlock(stockPos.below(),Blocks.STONE.defaultBlockState(),2);
            level.setBlock(stockPos,Blocks.CHEST.defaultBlockState(),2);
            net.minecraft.world.Container stock=de.villagerplus.village.Lager.publicContainerAt(level,stockPos);
            if(stock==null) throw new IllegalStateException("Mine safety public stock chest was not accessible");
            stock.clearContent();
            data.chest=java.util.Optional.of(stockPos);
            de.villagerplus.village.Villages.noteChunk(level.getChunkAt(bellPos));
            VillageData.Mine profileMine=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),0,e.getY()-2,false);
            Direction mineSide=Direction.EAST.getClockWise();
            for (int i=0;i<10;i++) {
                int baseY=-Math.min(i,2);
                int floorY=baseY-1;
                for (int lane=-1;lane<=1;lane++) {
                    level.setBlock(e.offset(i,floorY,lane),Blocks.STONE.defaultBlockState(),2);
                }
                if(Mining.supportSection(i)) {
                    for(int sign:new int[]{-1,1}) {
                        level.setBlock(e.relative(mineSide,sign*2).offset(i,floorY,0),Blocks.STONE.defaultBlockState(),2);
                    }
                }
                if(Mining.lightSection(i)) {
                    int sign=Math.floorMod(i/8,2)==0?-1:1;
                    level.setBlock(e.relative(mineSide,sign*2).offset(i,floorY,0),Blocks.STONE.defaultBlockState(),2);
                    level.setBlock(e.relative(mineSide,sign*3).offset(i,baseY,0),Blocks.STONE.defaultBlockState(),2);
                }
            }
            // The fixture cleared the approach corridor as well as the shaft; restore a
            // continuous supported surface under the villager before asking vanilla A* to route it.
            for (int x=-6;x<0;x++) for (int lane=-1;lane<=1;lane++) {
                level.setBlock(e.offset(x,-1,lane),Blocks.STONE.defaultBlockState(),2);
            }
            for(int sign:new int[]{-2,2}) {
                level.setBlock(e.relative(mineSide,sign).below(),Blocks.STONE.defaultBlockState(),2);
                level.setBlock(e.relative(Direction.WEST).relative(mineSide,sign).below(),Blocks.STONE.defaultBlockState(),2);
            }
            BlockPos cell=e.offset(2,-2,0);
            v=EntityTypes.VILLAGER.create(level,EntitySpawnReason.COMMAND);
            if(v==null) throw new IllegalStateException("Cannot create mine fixture");
            v.setPos(e.getX()-5.5,e.getY(),e.getZ()+.5);v.setOnGround(true);v.setNoAi(true);v.setInvulnerable(true);v.addTag("vp090_fixture");
            level.addFreshEntity(v);
            Ctx c=new Ctx(level,v,new JobState(),v.getAttachedOrCreate(VillagerPlus.MEMORY),null,level.getGameTime());
            BlockPos wornPickaxeTarget=e.offset(8,-2,2);
            level.setBlock(wornPickaxeTarget,Blocks.STONE.defaultBlockState(),2);
            ItemStack wornPickaxe=new ItemStack(Items.IRON_PICKAXE);
            wornPickaxe.setDamageValue(wornPickaxe.getMaxDamage()/2);
            v.getInventory().setItem(0,wornPickaxe);
            check(Mining.pickaxe(v.getInventory())==wornPickaxe,
                "partly worn pickaxe remains available for mine work");checks++;
            check(Mining.breakBlock(c,wornPickaxeTarget,wornPickaxe)&&level.getBlockState(wornPickaxeTarget).isAir(),
                "partly worn pickaxe removes natural mine stone");checks++;
            ItemStack spentPickaxe=new ItemStack(Items.IRON_PICKAXE);
            spentPickaxe.setDamageValue(spentPickaxe.getMaxDamage());
            v.getInventory().setItem(0,spentPickaxe);
            check(Mining.pickaxe(v.getInventory()).isEmpty(),
                "fully exhausted pickaxe is not selected for mine work");checks++;
            level.setBlock(wornPickaxeTarget,Blocks.STONE.defaultBlockState(),2);
            check(!Mining.breakBlock(c,wornPickaxeTarget,spentPickaxe)
                    &&level.getBlockState(wornPickaxeTarget).is(Blocks.STONE),
                "fully exhausted pickaxe cannot remove a mine block");checks++;
            v.getInventory().clearContent();
            check(Mining.safeCell(c,cell),"clear supported cell accepts villager");checks++;
            VillageData.Mine routeMine=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),4,e.getY()-2,false);
            check(Mining.nearestForReturnRecovery(routeMine,new Vec3(e.getX()+8.5,e.getY()-2,e.getZ()+.5))==4,
                "a displaced miner can rejoin the nearest saved stair section for its return");checks++;
            check(Mining.nearestForReturnRecovery(routeMine,new Vec3(e.getX()+11.5,e.getY()-2,e.getZ()+.5))==-1,
                "return recovery stays bounded instead of pulling a distant villager across the world");checks++;
            check(Mining.nextMineWaypoint(routeMine,-1,3).equals(e),
                "normal mine entry targets the real entrance cell first");checks++;
            var entryPath=v.getNavigation().createPath(e,0);
            check(entryPath!=null&&entryPath.canReach(),
                "vanilla villager navigation can reach a clear mine entrance without teleporting");checks++;
            BlockPos firstDescent=Mining.nextMineWaypoint(routeMine,0,3);
            check(firstDescent.equals(new BlockPos(e.getX()+1,e.getY()-1,e.getZ())),
                "entry advances only one stored terraced section at a time");checks++;
            var descentPath=v.getNavigation().createPath(firstDescent,0);
            check(descentPath!=null&&descentPath.canReach(),
                "vanilla navigation can reach the next supported one-block stair section");checks++;
            BlockPos returnStep=Mining.nextMineWaypoint(routeMine,2,0);
            check(returnStep.equals(new BlockPos(e.getX()+1,e.getY()-1,e.getZ())),
                "surface return walks one stored stair section backward at a time");checks++;
            v.setPos(e.getX()+2.5,e.getY()-2,e.getZ()+.5);
            var returnPath=v.getNavigation().createPath(returnStep,0);
            check(returnPath!=null&&returnPath.canReach(),
                "vanilla navigation can climb the same supported stair section toward the surface");checks++;
            check(Mining.cells(profileMine,0)==3&&Mining.cells(profileMine,7)==3,
                "every descent and horizontal mine section uses the same three-block clearance");checks++;
            check(Mining.previewOk(level,profileMine),
                "mine preview accepts a supported three-wide gallery with framed bays and solid sidewall light mounts");checks++;
            VillageData.Mine supportMine=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),2,e.getY()-2,false);
            BlockPos supportBase=e.offset(2,-2,0);
            v.getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,8));
            v.getInventory().setItem(1,new ItemStack(Items.OAK_FENCE,6));
            Mining.shape(c,supportMine,supportBase,Mining.cells(supportMine,2));
            boolean galleryFrame=true,sidewallFootings=true;
            for(int sign:new int[]{-1,1}) for(int y=0;y<3;y++) {
                galleryFrame &= level.getBlockState(supportBase.relative(mineSide,sign*2).above(y)).is(Blocks.OAK_FENCE);
            }
            for(int sign:new int[]{-1,1}) {
                sidewallFootings &= level.getBlockState(supportBase.relative(mineSide,sign*2).below()).is(Blocks.OAK_PLANKS);
            }
            for(int lane=-2;lane<=2;lane++) {
                galleryFrame &= level.getBlockState(supportBase.relative(mineSide,lane).above(3)).is(Blocks.OAK_PLANKS);
            }
            check(level.getBlockState(supportBase.below()).is(Blocks.OAK_PLANKS)&&galleryFrame&&sidewallFootings
                    &&JobUtil.count(v.getInventory(),stack->stack.is(Items.OAK_PLANKS))==0
                    &&JobUtil.count(v.getInventory(),stack->stack.is(Items.OAK_FENCE))==0,
                "a gallery bay lays a paid center walkway, matched sidewall footings, fence ribs, and roof beam");checks++;
            VillageData.Mine lightMine=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),3,e.getY()-2,false);
            BlockPos lightBase=e.offset(3,-2,0);
            int lightSign=Math.floorMod(3/8,2)==0?-1:1;
            BlockPos wallTorch=lightBase.relative(mineSide,lightSign*2);
            v.getInventory().setItem(0,new ItemStack(Items.OAK_PLANKS,1));
            v.getInventory().setItem(1,new ItemStack(Items.TORCH,1));
            Mining.shape(c,lightMine,lightBase,Mining.cells(lightMine,3));
            check(level.getBlockState(lightBase.below()).is(Blocks.OAK_PLANKS)
                    &&level.getBlockState(wallTorch).is(Blocks.WALL_TORCH)
                    &&level.getBlockState(wallTorch).getValue(net.minecraft.world.level.block.WallTorchBlock.FACING)==mineSide
                    &&JobUtil.count(v.getInventory(),stack->stack.is(Items.OAK_PLANKS))==0
                    &&JobUtil.count(v.getInventory(),stack->stack.is(Items.TORCH))==0,
                "the alternating sidewall torch faces into the gallery and is paid with the section walkway");checks++;
            Palette entrancePalette=new Palette(Blocks.SPRUCE_PLANKS,Blocks.BIRCH_PLANKS,Blocks.STRIPPED_SPRUCE_LOG,
                Blocks.COBBLESTONE,Blocks.SPRUCE_PLANKS,Blocks.SPRUCE_STAIRS,Blocks.SPRUCE_SLAB,
                Blocks.GLASS_PANE,Blocks.BIRCH_PLANKS,Blocks.SPRUCE_DOOR,Blocks.SPRUCE_FENCE);
            java.util.List<Mining.MineEntranceBlock> entrancePlan=Mining.mineEntrancePlan(profileMine,entrancePalette);
            java.util.Map<net.minecraft.world.item.Item,Integer> entranceCost=Mining.mineEntranceCost(profileMine,entrancePalette);
            int totalEntranceItems=entranceCost.values().stream().mapToInt(Integer::intValue).sum();
            int costSlot=0;
            for(var cost:entranceCost.entrySet()) stock.setItem(costSlot++,new ItemStack(cost.getKey(),cost.getValue()));
            BlockPos porch=e.relative(Direction.WEST);
            BlockPos blockedObstacle=porch.relative(mineSide,2).above(4);
            BlockPos roofObstacle=porch.above(4);
            level.setBlock(blockedObstacle,Blocks.STONE.defaultBlockState(),2);
            BlockState blockedTargetBefore=level.getBlockState(blockedObstacle);
            int blockedStockBefore=containerCount(stock);
            boolean blockedBuildResult=Mining.buildMineEntrance(level,ref,profileMine,entrancePalette);
            int blockedStockAfter=containerCount(stock);
            BlockState blockedTargetAfter=level.getBlockState(blockedObstacle);
            boolean blockedPaidBuild=!blockedBuildResult&&blockedStockAfter==totalEntranceItems;
            level.setBlock(roofObstacle,Blocks.AIR.defaultBlockState(),2);
            level.setBlock(blockedObstacle,Blocks.AIR.defaultBlockState(),2);
            boolean occupiedVisible=false;
            boolean occupiedBuildResult=false;
            int occupiedStockBefore=containerCount(stock);
            int occupiedStockAfter=occupiedStockBefore;
            BlockState occupiedTargetBefore=level.getBlockState(roofObstacle);
            BlockState occupiedTargetAfter=occupiedTargetBefore;
            boolean occupiedPaidBuild=false;
            if(pendingPortalOccupant==null) {
                portalOccupant=EntityTypes.VILLAGER.create(level,EntitySpawnReason.COMMAND);
                if(portalOccupant==null) throw new IllegalStateException("Cannot create mine portal occupant fixture");
                portalOccupant.setPos(roofObstacle.getX()+.5,roofObstacle.getY(),roofObstacle.getZ()+.5);
                portalOccupant.setNoAi(true);portalOccupant.setInvulnerable(true);portalOccupant.setNoGravity(true);
                // Stay indexed at the exact plan cell while the enclosing fixture restores the surrounding world.
                portalOccupant.noPhysics=true;portalOccupant.addTag("vp090_fixture");
                if(!level.addFreshEntity(portalOccupant)) {
                    portalOccupant.discard();
                    throw new IllegalStateException("Cannot add mine portal occupant fixture");
                }
                PENDING_PORTAL_OCCUPANTS.put(level,new PendingPortalOccupant(portalOccupant.getUUID(),roofObstacle.immutable(),
                    level.getGameTime(),portalOccupant.tickCount));
                keepPendingPortalOccupant=true;
                System.out.println("MINE SAFETY PREPARING portal occupant="+portalOccupant.getUUID()+" at "+roofObstacle.toShortString()+"; retry after one entity tick");
                return PREPARING;
            }
            try {
                if(!pendingPortalOccupant.cell().equals(roofObstacle)) throw new IllegalStateException("Pending portal occupant belongs to a different fixture cell");
                if(!(level.getEntity(pendingPortalOccupant.entity()) instanceof Villager existingOccupant))
                    throw new IllegalStateException("Ticked mine portal occupant fixture is missing from the entity lookup");
                portalOccupant=existingOccupant;
                occupiedVisible=level.getEntitiesOfClass(Villager.class,new net.minecraft.world.phys.AABB(roofObstacle),Villager::isAlive)
                    .contains(portalOccupant);
                occupiedTargetBefore=level.getBlockState(roofObstacle);
                occupiedBuildResult=Mining.buildMineEntrance(level,ref,profileMine,entrancePalette);
                occupiedStockAfter=containerCount(stock);
                occupiedTargetAfter=level.getBlockState(roofObstacle);
                occupiedPaidBuild=occupiedVisible&&!occupiedBuildResult&&occupiedStockAfter==totalEntranceItems;
            } finally {
                PENDING_PORTAL_OCCUPANTS.remove(level);
                if(portalOccupant!=null) portalOccupant.discard();
            }
            // Production refuses unsupported stone footings, so the fixture grounds all four before the paid build.
            for(int sign:new int[]{-2,2}) for(BlockPos footing:new BlockPos[]{e.relative(mineSide,sign),porch.relative(mineSide,sign)}) {
                level.setBlock(footing.below(),Blocks.STONE.defaultBlockState(),3);
            }
            for(int sign:new int[]{-3,3}) level.setBlock(porch.relative(mineSide,sign).below(),Blocks.STONE.defaultBlockState(),3); // outer lantern piers
            boolean built=Mining.buildMineEntrance(level,ref,profileMine,entrancePalette);
            boolean frame=entrancePlan.stream().allMatch(block->level.getBlockState(block.pos()).equals(block.state()));
            boolean symmetricSupports=true;
            for(int sign:new int[]{-2,2}) for(BlockPos footing:new BlockPos[]{e.relative(mineSide,sign),porch.relative(mineSide,sign)}) {
                symmetricSupports &= level.getBlockState(footing.below()).isFaceSturdy(level,footing.below(),Direction.UP)
                    && level.getBlockState(footing).equals(entrancePalette.base().defaultBlockState())
                    && level.getBlockState(footing.above()).equals(entrancePalette.frame().defaultBlockState())
                    && level.getBlockState(footing.above(2)).equals(entrancePalette.frame().defaultBlockState());
            }
            boolean openingClear=true;
            for(BlockPos opening:new BlockPos[]{e,porch}) for(int lane=-1;lane<=1;lane++) for(int y=0;y<3;y++) {
                openingClear &= level.getBlockState(opening.relative(mineSide,lane).above(y)).isAir();
            }
            boolean idempotentBuild=Mining.buildMineEntrance(level,ref,profileMine,entrancePalette);
            int finalStockCount=containerCount(stock);
            boolean noExtraCharge=idempotentBuild&&finalStockCount==0;
            check(symmetricSupports&&openingClear,
                "stone footings and timber uprights are mirrored at the portal and porch while both three-wide path cells stay open"
                +" [lastRejection="+Mining.lastEntranceRejection+", supports="+symmetricSupports+", opening="+openingClear+", built="+built+", frame="+frame+", e="+e.toShortString()+", porch="+porch.toShortString()
                +", sampleFooting="+level.getBlockState(e.relative(mineSide,2))+", above="+level.getBlockState(e.relative(mineSide,2).above())
                +", above2="+level.getBlockState(e.relative(mineSide,2).above(2))+", below="+level.getBlockState(e.relative(mineSide,2).below())
                +", wantBase="+entrancePalette.base()+", wantFrame="+entrancePalette.frame()+"]");checks++;
            check(blockedPaidBuild&&occupiedPaidBuild&&built&&frame&&noExtraCharge,
                "the complete gabled mine portal waits for an empty site and a separately spawned, query-visible villager, uses paid plan materials, and persists idempotently"
                    + " [blockedPaidBuild="+blockedPaidBuild+" (build="+blockedBuildResult+", target="+blockedTargetBefore+" -> "+blockedTargetAfter
                    +", stock="+blockedStockBefore+" -> "+blockedStockAfter+"), occupiedPaidBuild="+occupiedPaidBuild
                    +" (visible="+occupiedVisible+", build="+occupiedBuildResult+", target="+occupiedTargetBefore+" -> "+occupiedTargetAfter
                    +", stock="+occupiedStockBefore+" -> "+occupiedStockAfter+", occupant="+portalOccupant.blockPosition()+"/"+portalOccupant.getBoundingBox()+")"
                    +", built="+built+", frame="+frame+", noExtraCharge="+noExtraCharge
                    +" (idempotentBuild="+idempotentBuild+", stock="+finalStockCount+", contents="+containerContents(stock)+")"
                    +", planSize="+entrancePlan.size()+", planCost="+entranceCost+", blockedObstacleInPlan="+entrancePlan.stream().anyMatch(block->block.pos().equals(blockedObstacle))
                    +", occupiedObstacleInPlan="+entrancePlan.stream().anyMatch(block->block.pos().equals(roofObstacle))
                    +", blockedExpected="+entrancePlan.stream().filter(block->block.pos().equals(blockedObstacle)).map(Mining.MineEntranceBlock::state).findFirst().orElse(null)
                    +", occupiedExpected="+entrancePlan.stream().filter(block->block.pos().equals(roofObstacle)).map(Mining.MineEntranceBlock::state).findFirst().orElse(null)
                    +", planMismatches="+entrancePlanMismatches(level,entrancePlan)
                    +", stockRegistered="+de.villagerplus.village.Lager.materialChests(level,ref).contains(stockPos)+"]");checks++;
            long reservationAt=level.getGameTime();
            Mining.MineRoute activeRoute=new Mining.MineRoute(level.dimension().identifier().toString(),e,
                Direction.EAST.get2DDataValue(),0,profileMine.targetY,reservationAt+40);
            check(Mining.activeSurfaceReservation(activeRoute,level,profileMine,reservationAt+40,reservationAt)
                    &&!Mining.activeSurfaceReservation(activeRoute,level,profileMine,reservationAt,reservationAt),
                "one surface miner retains admission only while its short work reservation is live");checks++;
            JobState admissionState=new JobState();
            Mining.reserveMineAdmission(admissionState,reservationAt);
            Mining.MineRoute fullAdmission=new Mining.MineRoute(level.dimension().identifier().toString(),e,
                Direction.EAST.get2DDataValue(),0,profileMine.targetY,reservationAt+80);
            check(admissionState.miningUntil==reservationAt+80
                    &&Mining.activeSurfaceReservation(fullAdmission,level,profileMine,admissionState.miningUntil,reservationAt+79)
                    &&!Mining.activeSurfaceReservation(fullAdmission,level,profileMine,admissionState.miningUntil,reservationAt+80),
                "surface admission and route lease share the full 80-tick lifetime");checks++;
            Mining.MineRoute workLease=new Mining.MineRoute(level.dimension().identifier().toString(),e,
                Direction.EAST.get2DDataValue(),0,profileMine.targetY,reservationAt+80,2);
            Mining.MineRoute reloadedWorkLease=Mining.MineRoute.CODEC.parse(JsonOps.INSTANCE,
                Mining.MineRoute.CODEC.encodeStart(JsonOps.INSTANCE,workLease).getOrThrow()).getOrThrow();
            check(reloadedWorkLease.equals(workLease)&&reloadedWorkLease.workSlot()==2,
                "assigned work lane survives the persisted route codec");checks++;
            check(Mining.mineOutputForKitRoom(new ItemStack(Items.DIAMOND))
                    &&!Mining.mineOutputForKitRoom(new ItemStack(Items.COBBLESTONE))
                    &&!Mining.mineOutputForKitRoom(new ItemStack(Items.OAK_FENCE))
                    &&!Mining.mineOutputForKitRoom(new ItemStack(Items.OAK_PLANKS))
                    &&!Mining.mineOutputForKitRoom(new ItemStack(Items.TORCH))
                    &&!Mining.mineOutputForKitRoom(new ItemStack(Items.IRON_PICKAXE)),
                "kit-space delivery selects mined output while retaining terrace cobble, supports, lights, and tools");checks++;

            for(int x=-6;x<=1;x++) for(int z=-8;z<=8;z++) {
                level.setBlock(e.offset(x,-1,z),Blocks.STONE.defaultBlockState(),2);
            }
            v.setPos(e.getX()-5.5,e.getY(),e.getZ()+.5);
            VillageData.Mine queueMine=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),0,e.getY()-5,false);
            java.util.List<BlockPos> waitingStands=new java.util.ArrayList<>();
            boolean allWaitingSafe=true;
            java.util.List<String> waitingFailures=new java.util.ArrayList<>();
            for(int slot=0;slot<18;slot++) {
                BlockPos stand=Mining.mineWaitingStand(level,queueMine,slot);
                boolean standSafe=stand!=null&&Mining.mineWaitingCellSafe(c,stand);
                if(!standSafe) waitingFailures.add(slot+"@"+(stand==null?"null":stand.toShortString()));
                allWaitingSafe &= standSafe;
                waitingStands.add(stand);
            }
            boolean spaced=true;
            for(int i=0;i<waitingStands.size();i++) for(int j=i+1;j<waitingStands.size();j++) {
                BlockPos a=waitingStands.get(i),b=waitingStands.get(j);
                spaced &= a!=null&&b!=null&&a.distSqr(b)>=4;
            }
            check(allWaitingSafe&&spaced,
                "all 18 surface waiting pads have supported headroom and remain at least two blocks apart"
                +" [safe="+allWaitingSafe+", spaced="+spaced+", unsafe="+waitingFailures+", e="+e.toShortString()+"]");checks++;

            java.util.List<BlockPos> workStands=new java.util.ArrayList<>();
            boolean allWorkStandsSafe=true,workStandsDistinct=true;
            for(int slot=0;slot<Mining.MINE_WORK_STAND_COUNT;slot++) {
                BlockPos workStand=Mining.mineWorkStand(queueMine,0,slot);
                allWorkStandsSafe &= workStand!=null&&Mining.safeCell(c,workStand);
                workStands.add(workStand);
            }
            for(int i=0;i<workStands.size();i++) for(int j=i+1;j<workStands.size();j++) {
                BlockPos a=workStands.get(i),b=workStands.get(j);
                workStandsDistinct &= a!=null&&b!=null&&!a.equals(b)&&a.distSqr(b)>=1;
            }
            check(allWorkStandsSafe&&workStandsDistinct,
                "three bounded work lanes have independent supported cells inside the cleared mine passage");checks++;

            BlockPos blockedWorkStand=workStands.get(0);
            BlockState openWorkCell=level.getBlockState(blockedWorkStand);
            level.setBlock(blockedWorkStand,Blocks.STONE.defaultBlockState(),2);
            Mining.MineWalk blockedStep=Mining.walkMineStep(c,queueMine,blockedWorkStand);
            level.setBlock(blockedWorkStand,openWorkCell,2);
            check(blockedStep==Mining.MineWalk.STUCK&&!queueMine.done&&queueMine.index==0,
                "a newly obstructed work lane retries without closing or advancing the paid mine");checks++;
            level.setBlock(blockedWorkStand,Blocks.WATER.defaultBlockState(),2);
            Mining.MineWalk wetStep=Mining.walkMineStep(c,queueMine,blockedWorkStand);
            level.setBlock(blockedWorkStand,openWorkCell,2);
            check(wetStep==Mining.MineWalk.BLOCKED,
                "a wet shaft landing remains a fatal safety block instead of a navigation retry");checks++;

            for(int slot=0;slot<Mining.MINE_WORK_STAND_COUNT;slot++) {
                Villager assigned=EntityTypes.VILLAGER.create(level,EntitySpawnReason.COMMAND);
                if(assigned==null) throw new IllegalStateException("Cannot create assigned mine fixture worker");
				assigned.setPos(e.getX()-10-slot,e.getY(),e.getZ()+8);
				// Active route leases must be visible to the bounded entity query. The fixture positions
				// can cross into neighboring chunks, so load those chunks before inserting the workers.
				level.getChunkAt(assigned.blockPosition());
                assigned.setNoAi(true);assigned.setInvulnerable(true);assigned.addTag("vp090_fixture");
				if(!level.addFreshEntity(assigned)) throw new IllegalStateException("Cannot add assigned mine fixture worker");
				assignedMiners.add(assigned);
                assigned.setAttached(VillagerPlus.MINING_ROUTE,new Mining.MineRoute(
                    level.dimension().identifier().toString(),e,Direction.EAST.get2DDataValue(),queueMine.index,
                    queueMine.targetY,level.getGameTime()+80,slot));
            }
            int visibleRouteWorkers=Mining.liveMineRouteWorkerCount(level,queueMine);
            int availableWorkSlot=Mining.findMineWorkSlot(c,queueMine);
            check(visibleRouteWorkers==Mining.MINE_WORK_STAND_COUNT&&availableWorkSlot==-1,
				"three live work-lane leases prevent a fourth miner from reusing their target cells (visible="
					+visibleRouteWorkers+", selected="+availableWorkSlot+")");checks++;
			for(int slot=1;slot<assignedMiners.size();slot++) {
				assignedMiners.get(slot).setAttached(VillagerPlus.MINING_ROUTE,new Mining.MineRoute(
					level.dimension().identifier().toString(),e,Direction.EAST.get2DDataValue(),queueMine.index,
					queueMine.targetY,level.getGameTime()-1,slot));
			}
			Villager laneZeroWorker=assignedMiners.get(0);
			BlockPos laneZero=workStands.get(0);
			laneZeroWorker.setPos(laneZero.getX()+0.5,laneZero.getY(),laneZero.getZ()+0.5);
			// The entrance admission is serial: a villager without an admission waits while another route holder stands
			// at portal level. The lane choice itself is under test, so the checking miner holds its own admission.
			v.setAttached(VillagerPlus.MINING_ROUTE,new Mining.MineRoute(
				level.dimension().identifier().toString(),e,Direction.EAST.get2DDataValue(),queueMine.index,
				queueMine.targetY,level.getGameTime()+80,-1));
			int adjacentWorkSlot=Mining.findMineWorkSlot(c,queueMine);
			check(adjacentWorkSlot>0,
				"a miner in the center lane leaves either one-block-spaced side lane available (selected="+adjacentWorkSlot+") ["+Mining.workSlotDiagnostics(c,queueMine)+"]");checks++;
			v.removeAttached(VillagerPlus.MINING_ROUTE); // the admission was only lent to the lane-choice check
			laneZeroWorker.setPos(e.getX()-10,e.getY(),e.getZ()+8);
            for(int slot=0;slot<assignedMiners.size();slot++) {
                assignedMiners.get(slot).setAttached(VillagerPlus.MINING_ROUTE,new Mining.MineRoute(
                    level.dimension().identifier().toString(),e,Direction.EAST.get2DDataValue(),queueMine.index,
                    queueMine.targetY,level.getGameTime()-1,slot));
            }
            check(Mining.findMineWorkSlot(c,queueMine)>=0,
                "expired work-lane leases release their assignments for a safe retry");checks++;
			VillageData.Mine undergroundMine=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),2,e.getY()-5,false);
			BlockPos undergroundStand=Mining.mineWorkStand(undergroundMine,1,0);
			laneZeroWorker.setPos(undergroundStand.getX()+0.5,undergroundStand.getY(),undergroundStand.getZ()+0.5);
			laneZeroWorker.setAttached(VillagerPlus.MINING_ROUTE,new Mining.MineRoute(
				level.dimension().identifier().toString(),e,Direction.EAST.get2DDataValue(),undergroundMine.index,
				undergroundMine.targetY,level.getGameTime()-1,0));
			check(Mining.liveMineRouteWorkerCount(level,undergroundMine)==1,
				"an expired underground miner retains its work lane until it physically exits the shaft");checks++;
            for(Villager assigned:assignedMiners) assigned.discard();

            Mining.rememberMineWaiting(v,level,queueMine,3);
            check(v.getAttached(VillagerPlus.MINING_ROUTE).index()==-4&&Mining.taggedMine(v,level)==null,
                "a persisted negative-index route reserves a surface pad without masquerading as an underground miner");checks++;
            v.removeAttached(VillagerPlus.MINING_ROUTE);

            v.setPos(e.getX()-5.5,e.getY(),e.getZ()+.5);
            Mining.rememberMineRoute(v,level,routeMine);
            VillageData.Mine reloadedRoute=Mining.taggedMine(v,level);
            check(reloadedRoute!=null&&reloadedRoute.index==routeMine.index
                    &&Mining.shouldExitMine(reloadedRoute,new JobState().miningUntil,level.getGameTime())
                    &&Mining.nextMineWaypoint(reloadedRoute,2,0).equals(returnStep),
                "saved route survives a transient job-state reload and resumes toward the surface");checks++;
            Vec3 flatRoute=new Vec3(e.getX()+2.5,e.getY()-2,e.getZ()+.5);
            check(Mining.mineMovementAllowed(routeMine,flatRoute,new Vec3(.08,0,0),.6,1.95),
                "movement along a supported terraced mine corridor stays allowed");checks++;
            check(Mining.mineMovementAllowed(routeMine,flatRoute,new Vec3(0,0,.3),.6,1.95),
                "ordinary lateral movement remains inside the three-wide passage for a standard villager");checks++;
            check(!Mining.mineMovementAllowed(routeMine,flatRoute,new Vec3(0,0,1.0),1.4,1.95),
                "the 1.4-wide collision profile cannot be shoved past the edge of the three-wide passage");checks++;
            check(!Mining.mineMovementAllowed(routeMine,flatRoute,new Vec3(1.5,0,0),.6,1.95),
                "movement beyond the last saved mine section is rejected");checks++;
            check(!Mining.mineMovementAllowed(routeMine,flatRoute,new Vec3(0,-.25,0),.6,1.95),
                "movement below the saved supported floor is rejected");checks++;
            check(Mining.mineMovementAllowed(routeMine,flatRoute.add(0,.0,.3),new Vec3(0,0,-.12),.6,1.95),
                "a villager already nudged off-axis may move back toward the saved corridor");checks++;
            Mining.rememberMineRoute(v,level,routeMine);
            v.setPos(flatRoute.x,flatRoute.y,flatRoute.z);
            Vec3 beforeRouteMove=v.position();
            v.move(MoverType.SELF,new Vec3(.08,0,0));
            check(v.getX()>beforeRouteMove.x+.07,
                "actual server Entity.move accepts a villager step along its saved mine route");checks++;
            Vec3 beforeRejectedMove=v.position();
            v.setDeltaMovement(new Vec3(0,0,1.4));
            v.move(MoverType.SELF,new Vec3(0,0,1.3));
            check(v.position().distanceToSqr(beforeRejectedMove)<1.0E-12
                    &&v.getDeltaMovement().lengthSqr()<1.0E-12,
                "actual server Entity.move rejects a lateral shove and clears its movement impulse");checks++;
            VillageData.Mine waitingMine=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),0,e.getY()-5,false);
            BlockPos waitingFloor=e.below();
            level.setBlock(waitingFloor,Blocks.AIR.defaultBlockState(),2);
            level.setBlock(waitingFloor.below(),Blocks.STONE.defaultBlockState(),2);
            v.getInventory().clearContent();
            Mining.dig(c,null,waitingMine,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
            check(waitingMine.index==0&&!waitingMine.done&&level.getBlockState(waitingFloor).isAir()
                    &&c.st().activity.contains("sichere Minenterrasse"),
                "actual dig path waits without cobblestone and preserves the saved section cursor");checks++;
            level.setBlock(waitingFloor,Blocks.STONE.defaultBlockState(),2);
            level.setBlock(waitingFloor.below(),Blocks.AIR.defaultBlockState(),2);

            BlockPos unloadedEntrance=e.east(20000);
            if(level.hasChunkAt(unloadedEntrance)) throw new IllegalStateException("Unloaded mining fixture chunk is already loaded");
            VillageData.Mine unloadedMine=new VillageData.Mine(unloadedEntrance,Direction.EAST.get2DDataValue(),0,e.getY()-5,false);
            Mining.dig(c,null,unloadedMine,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
            check(unloadedMine.index==0&&!unloadedMine.done&&!level.hasChunkAt(unloadedEntrance),
                "actual dig path leaves an unloaded section untouched and waits at the same cursor");checks++;
            check(Mining.waitForUnloadedApproach(c,unloadedEntrance)&&!level.hasChunkAt(unloadedEntrance),
                "pre-entry guard waits before safeCell can classify unloaded support as a fatal hole");checks++;

            int untouchedEdgeChunkX=e.east(30_000).getX()>>4;
            BlockPos boundaryBase=new BlockPos((untouchedEdgeChunkX<<4)+15,e.getY(),e.getZ());
            BlockPos boundaryNeighbor=boundaryBase.east();
            if(level.hasChunkAt(boundaryBase)||level.hasChunkAt(boundaryNeighbor))
                throw new IllegalStateException("Distant mining hazard edge fixture was already loaded");
            level.getChunkAt(boundaryBase);
            if(!level.hasChunkAt(boundaryBase)||level.hasChunkAt(boundaryNeighbor))
                throw new IllegalStateException("Loading the mining hazard boundary also loaded its neighboring chunk");
            check(Mining.hazard(level,boundaryBase)&&!level.hasChunkAt(boundaryNeighbor),
                "unloaded neighboring chunk is treated as hazardous without being loaded");checks++;

            BlockPos fluidProbe=cell;
            BlockPos fluidNeighbor=fluidProbe.above();
            BlockState previousFluidNeighbor=level.getBlockState(fluidNeighbor);
            level.setBlock(fluidNeighbor,Blocks.WATER.defaultBlockState(),2);
            check(Mining.hazard(level,fluidProbe),"loaded adjacent fluid remains a mine hazard");checks++;
            level.setBlock(fluidNeighbor,previousFluidNeighbor,2);

            BlockPos terraceFloor=cell.below();
            level.setBlock(terraceFloor,Blocks.AIR.defaultBlockState(),2);
            level.setBlock(terraceFloor.below(),Blocks.STONE.defaultBlockState(),2);
            check(Mining.oneBlockTerraceGap(level,terraceFloor),"single dry supported terrace gap is repairable");checks++;
            level.setBlock(terraceFloor.below(),Blocks.AIR.defaultBlockState(),2);
            check(!Mining.oneBlockTerraceGap(level,terraceFloor),"deeper cavity is rejected rather than bridged as a terrace");checks++;
            level.setBlock(terraceFloor.below(),Blocks.STONE.defaultBlockState(),2);
            v.getInventory().setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE));
            check(Mining.patchFloor(c,terraceFloor)
                    && level.getBlockState(terraceFloor).is(Blocks.COBBLESTONE)
                    && v.getInventory().getItem(0).isEmpty(),
                "one-block terrace is visibly repaired with and consumes real cobblestone");checks++;
            level.setBlock(terraceFloor,Blocks.AIR.defaultBlockState(),2);
            check(!Mining.patchFloor(c,terraceFloor)&&level.getBlockState(terraceFloor).isAir(),
                "terrace waits without material instead of fabricating a landing");checks++;
            level.setBlock(terraceFloor,Blocks.CHEST.defaultBlockState(),2);
            check(!Mining.patchFloor(c,terraceFloor)&&level.getBlockState(terraceFloor).is(Blocks.CHEST)
                    &&!Mining.oneBlockTerraceGap(level,terraceFloor),
                "block-entity terrace target is rejected without replacing its container");checks++;
            level.setBlock(terraceFloor,Blocks.STONE.defaultBlockState(),2);
            var oldPos=v.position();
            level.setBlock(cell.above(),Blocks.STONE.defaultBlockState(),2);
            check(!Mining.safeCell(c,cell)&&v.position().equals(oldPos),
                "blocked route headroom rejects a normal navigation step without moving the villager");checks++;
            level.setBlock(cell.above(),Blocks.AIR.defaultBlockState(),2);
            level.setBlock(cell.below(),Blocks.AIR.defaultBlockState(),2);
            check(!Mining.safeCell(c,cell),"missing floor rejects cell");checks++;
            level.setBlock(cell.below(),Blocks.STONE.defaultBlockState(),2);
            level.setBlock(cell,Blocks.WATER.defaultBlockState(),2);
            check(!Mining.safeCell(c,cell),"water rejects cell");checks++;
            level.setBlock(cell,Blocks.AIR.defaultBlockState(),2);
            check(Mining.safeCell(c,cell),"clear route cell accepts a normal navigation step");checks++;
            v.setPos(cell.getX()+.5,cell.getY(),cell.getZ()+.5);
            // Include the first saved horizontal section after the five-block descent.
            var m=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),6,e.getY()-5,true);
            // The closure planner estimates the original surface from the two flanks outside the saved shaft.
            // Keep this fixture deliberately flat instead of letting its cleared volume look like a ravine.
            for(int section=0;section<Mining.openedMineSections(m);section++) {
                BlockPos route=e.relative(Direction.EAST,section);
                for(int probeLane:new int[]{-3,3}) {
                    BlockPos probe=new BlockPos(route.getX(),e.getY()-1,route.getZ()).relative(mineSide,probeLane);
                    if(!level.hasChunkAt(probe)) throw new IllegalStateException("Mining surface probe is not loaded at "+probe.toShortString());
                    for(int y=e.getY()-4;y<level.getMaxY();y++) {
                        BlockPos skyProbe=new BlockPos(probe.getX(),y,probe.getZ());
                        BlockState state=level.getBlockState(skyProbe);
                        if(level.getBlockEntity(skyProbe)!=null||state.hasBlockEntity())
                            throw new IllegalStateException("Mining surface probe overlaps a protected block entity at "+skyProbe.toShortString());
                        rememberAndClear(level,before,skyProbe);
                    }
                    level.setBlock(probe,Blocks.STONE.defaultBlockState(),2);
                }
            }
            data.mine=Optional.of(m);
            check(!Mining.canSeal(level,m),"miner in descending shaft prevents sealing");checks++;
            Mining.barricade(level,ref,m,false);
            check(level.getBlockState(e).isAir()&&level.getBlockState(cell).isAir()
                    &&stock.getItem(0).isEmpty()&&stock.getItem(1).isEmpty(),"occupied mine keeps entrance and shaft open without consuming stock");checks++;
            v.setPos(e.getX()-6.5,e.getY(),e.getZ()+.5);
            check(Mining.canSeal(level,m),"empty loaded shaft can close");checks++;
            stock.setItem(0,new ItemStack(Items.OAK_PLANKS,18));
            stock.setItem(1,new ItemStack(Items.SPRUCE_SIGN,1));
            check(stock.getItem(1).is(Items.SPRUCE_SIGN),"fixture seeds the closure sign before registering its material chest");checks++;
            data.chest=Optional.of(stockPos);
            data.extra.oldMines.add(m);data.mine=Optional.empty();
            stock.setItem(0,new ItemStack(Items.OAK_PLANKS,17));
            check(stock.getItem(0).getCount()==17&&stock.getItem(1).is(Items.SPRUCE_SIGN),
                "short-stock state is established before tidyOldMines");checks++;
            boolean openingsClearBefore=true,hiddenOpenRoof=false;
            BlockPos roofOccluder=null;
            for(int lane=-1;lane<=1;lane++) for(int y=0;y<3;y++) {
                BlockPos portal=e.relative(mineSide,lane).above(y);
                openingsClearBefore&=level.getBlockState(portal).isAir();
            }
            for(int section=0;section<m.index;section++) for(int lane=-1;lane<=1;lane++) {
                BlockPos roof=e.relative(Direction.EAST,section).below(section).relative(mineSide,lane).above(3);
                // Clear only these nine narrow roof columns. One cell is then hidden from
                // direct sky to verify that closure follows the saved stair geometry.
                for (int y = roof.getY(); y < level.getMaxY(); y++) {
                    BlockPos skyCell = new BlockPos(roof.getX(), y, roof.getZ());
                    BlockState state = level.getBlockState(skyCell);
                    if (level.getBlockEntity(skyCell) != null || state.hasBlockEntity()) {
                        throw new IllegalStateException("Mining sky fixture overlaps a protected block entity at " + skyCell.toShortString());
                    }
                    rememberAndClear(level, before, skyCell);
                }
				if(section==1&&lane==0) {
					roofOccluder=roof.above();
					level.setBlock(roofOccluder,Blocks.STONE.defaultBlockState(),3);
					hiddenOpenRoof=hasDirectSkyOccluder(level,roof);
				} else openingsClearBefore&=!hasDirectSkyOccluder(level,roof);
				openingsClearBefore&=level.getBlockState(roof).isAir();
            }
			check(openingsClearBefore&&hiddenOpenRoof,"the saved descent and first horizontal section have eighteen open roof cells, including one directly covered by stone, before tidyOldMines");checks++;
			var plannedSeal=Mining.mineSurfaceSealPlan(level,m);
			boolean surfaceCapPlanned=plannedSeal.loaded();
			for(int section=1;section<=3;section++) for(int lane=-1;lane<=1;lane++) {
				BlockPos cap=e.relative(Direction.EAST,section).below().relative(mineSide,lane);
				surfaceCapPlanned&=plannedSeal.coverCells().contains(cap);
			}
			check(surfaceCapPlanned,"surface-intersecting shaft sections plan full caps in the excavated terrain block below the heightmap first-air cell");checks++;
            Mining.tidyOldMines(level,ref);
            boolean roofOpen=true;
            for(int section=0;section<m.index;section++) for(int lane=-1;lane<=1;lane++) {
                BlockPos roof=e.relative(Direction.EAST,section).below(section).relative(mineSide,lane).above(3);
                roofOpen&=level.getBlockState(roof).isAir();
            }
            check(level.getBlockState(e).isAir()&&roofOpen&&stock.getItem(0).getCount()==17&&stock.getItem(1).getCount()==1,
                "short public stock leaves all mine openings unchanged and is not partially consumed"
                    +" [portal="+level.getBlockState(e)+", roofOpen="+roofOpen+", planks="+stock.getItem(0)+", sign="+stock.getItem(1)+"]");checks++;
            // Closure now pays for the portal, the exposed surface cap, and every
            // underground roof cell in the saved route; seed enough stock to reach
            // the roof/shape assertions instead of accidentally testing the shortage gate twice.
            stock.setItem(0,new ItemStack(Items.OAK_PLANKS,64));
            MineSealCost expectedSealCost=mineSealCost(level,m);
            int expectedPlanksRemaining=64-expectedSealCost.paidTargets().size();
            List<BlockPos> reusedApproachCap=new ArrayList<>();
            for(int lane=-1;lane<=1;lane++) reusedApproachCap.add(e.relative(Direction.EAST,1).below().relative(mineSide,lane));
            check(expectedSealCost.paidTargets().size()==21&&expectedSealCost.existingFullTargets().size()==3
                    &&expectedSealCost.existingFullTargets().containsAll(reusedApproachCap),
                "seal plan reuses the three full approach-floor blocks under section one instead of charging for them"
                    + " [paid="+expectedSealCost.paidTargets()+", reused="+expectedSealCost.existingFullTargets()+"]");checks++;
            Mining.tidyOldMines(level,ref);
			boolean undergroundRoofSealed=true, aboveSurfaceRoofClear=true;
			for(int section=0;section<m.index;section++) for(int lane=-1;lane<=1;lane++) {
				BlockPos roof=e.relative(Direction.EAST,section).below(section).relative(mineSide,lane).above(3);
				if (roof.getY() < e.getY()) undergroundRoofSealed &= level.getBlockState(roof).is(Blocks.OAK_PLANKS);
				else aboveSurfaceRoofClear &= level.getBlockState(roof).isAir();
			}
			boolean surfaceCoverSturdy = Mining.mineSurfaceCoverCells(m).stream().allMatch(pos ->
				level.getBlockState(pos).isFaceSturdy(level, pos, Direction.UP));
			boolean surfaceCapsSealed=true;
			for(int section=1;section<=3;section++) for(int lane=-1;lane<=1;lane++) {
				BlockPos cap=e.relative(Direction.EAST,section).below().relative(mineSide,lane);
				surfaceCapsSealed&=level.getBlockState(cap).isCollisionShapeFullBlock(level,cap);
			}
			check(level.getBlockState(e).is(Blocks.OAK_PLANKS)&&level.getBlockState(cell).isAir()
					&&undergroundRoofSealed&&aboveSurfaceRoofClear&&surfaceCoverSturdy&&surfaceCapsSealed
					&&roofOccluder!=null&&level.getBlockState(roofOccluder).is(Blocks.STONE)
					&&stock.getItem(0).is(Items.OAK_PLANKS)&&stock.getItem(0).getCount()==expectedPlanksRemaining&&stock.getItem(1).isEmpty(),
				"retry seals underground route roofs and portal, plugs the open surface-level shaft cells, leaves open-sky cells clear, and preserves shaft and occluder"
					+ " [portal=" + level.getBlockState(e) + ", shaft=" + level.getBlockState(cell)
					+ ", underground=" + undergroundRoofSealed + ", aboveSurface=" + aboveSurfaceRoofClear + ", caps=" + surfaceCapsSealed
					+ ", cap=" + surfaceCoverSturdy + ", occluder=" + (roofOccluder == null ? "null" : level.getBlockState(roofOccluder))
					+ ", paidTargets=" + expectedSealCost.paidTargets() + ", existingFullTargets=" + expectedSealCost.existingFullTargets()
					+ ", expectedPlanksRemaining=" + expectedPlanksRemaining + ", actualPlanks=" + stock.getItem(0)
					+ ", sign=" + stock.getItem(1) + "]");checks++;
			var activeMine=new VillageData.Mine(e.offset(10,0,0),Direction.EAST.get2DDataValue(),0,e.getY()-2,false);
			BlockPos activeEntry=activeMine.entrance;
			for(int probeLane:new int[]{-3,3}) {
				BlockPos probe=activeEntry.relative(mineSide,probeLane);
				for(int y=activeEntry.getY()-4;y<level.getMaxY();y++) rememberAndClear(level,before,new BlockPos(probe.getX(),y,probe.getZ()));
				level.setBlock(probe.below(),Blocks.STONE.defaultBlockState(),2);
			}
			data.mine=Optional.of(activeMine);
			stock.setItem(0,new ItemStack(Items.OAK_PLANKS,64));
			stock.setItem(1,new ItemStack(Items.SPRUCE_SIGN,1));
			int stockBeforeActiveAttempt=containerCount(stock);
			Mining.scheduleMineSealRetry(level,activeMine,false,level.getGameTime());
			Mining.tickPendingMineSeals(level,level.getGameTime());
			boolean activeEntryOpen=true;
			for(int lane=-1;lane<=1;lane++) for(int y=0;y<3;y++) {
				BlockPos pos=activeEntry.relative(mineSide,lane).above(y);
				activeEntryOpen&=level.getBlockState(pos).isAir();
			}
			check(activeEntryOpen&&containerCount(stock)==stockBeforeActiveAttempt,
				"a stale deferred close is discarded when the route is active, leaving its entrance open and public stock untouched");checks++;
            var horizontal=new VillageData.Mine(e,Direction.EAST.get2DDataValue(),6,e.getY(),true);
            v.setPos(e.getX()+5.5,e.getY(),e.getZ()-4.5);
            check(!Mining.canSeal(level,horizontal),"occupant in side branch prevents sealing");checks++;
            var remote=new VillageData.Mine(e.east(20000),Direction.EAST.get2DDataValue(),3,e.getY()-5,true);
            check(!Mining.canSeal(level,remote),"unloaded mine cannot be assumed empty");checks++;
            return checks;
        } finally {
            for(Villager assigned:assignedMiners) assigned.discard();
            if(v!=null)v.discard();
            if(!keepPendingPortalOccupant) {
                discardPendingPortalOccupant(level);
                if(portalOccupant!=null) portalOccupant.discard();
                releaseFixtureTickets(level);
            }
            for (Map.Entry<BlockPos, Cell> entry : before.entrySet()) {
                BlockPos pos = entry.getKey();
                Cell cell = entry.getValue();
                level.setBlock(pos, cell.state(), 2);
                if (cell.blockEntity() == null) {
                    if (level.getBlockEntity(pos) != null) level.removeBlockEntity(pos);
                } else {
                    BlockEntity restored = BlockEntity.loadStatic(pos, cell.state(), cell.blockEntity().copy(), level.registryAccess());
                    if (restored != null) level.setBlockEntity(restored);
                }
            }
        }
    }

    /** Tickets the chunks that will hold synthetic miners, then lets the caller retry after promotion. */
    private static boolean prepareEntityTickingFixture(ServerLevel level, BlockPos e) {
        Set<ChunkPos> required = fixtureEntityChunks(e);
        FixtureLease previous = FIXTURE_TICKETS.get(level);
        if (previous != null && !previous.chunks().equals(required)) {
            discardPendingPortalOccupant(level);
            releaseFixtureTickets(level);
        }

        Set<ChunkPos> added = new LinkedHashSet<>();
        try {
            for (ChunkPos chunk : required) {
                // Radius 2 promotes the ticketed center chunk to ENTITY_TICKING on 26.2.
                level.getChunkSource().addTicketWithRadius(Anchor.WALL_WORK, chunk, FIXTURE_TICKET_RADIUS);
                added.add(chunk);
            }
            FIXTURE_TICKETS.put(level, new FixtureLease(Set.copyOf(added)));

            boolean ready = true;
            StringBuilder statuses = new StringBuilder();
            for (ChunkPos chunk : required) {
                var loaded = level.getChunkSource().getChunkNow(chunk.x(), chunk.z());
                FullChunkStatus status = loaded == null ? null : loaded.getFullStatus();
                if (statuses.length() > 0) statuses.append(", ");
                statuses.append(chunk).append('=').append(status == null ? "UNLOADED" : status);
                if (status == null || !status.isOrAfter(FullChunkStatus.ENTITY_TICKING)) ready = false;
            }
            if (!ready) {
                String currentStatus=statuses.toString();
                String previousStatus=LAST_PREPARING_STATUS.put(level,currentStatus);
                if(!currentStatus.equals(previousStatus)) {
                    System.out.println("MINE SAFETY PREPARING entity fixture chunks=" + currentStatus
                        + "; wait for server ticks, then retry /vptest090 mining; no checks counted");
                }
                return false;
            }
            LAST_PREPARING_STATUS.remove(level);
            return true;
        } catch (RuntimeException | Error failure) {
            releaseTickets(level, added);
            FIXTURE_TICKETS.remove(level);
            LAST_PREPARING_STATUS.remove(level);
            discardPendingPortalOccupant(level);
            throw failure;
        }
    }

    private static Set<ChunkPos> fixtureEntityChunks(BlockPos e) {
        Set<ChunkPos> chunks = new LinkedHashSet<>();
        for (BlockPos actor : new BlockPos[]{
                e.offset(-6, 0, 0), e.offset(2, 0, 0), e.west(), e.offset(-7, 0, 0), e.offset(5, 0, -5)}) {
            chunks.add(ChunkPos.containing(actor));
        }
        for (int slot = 0; slot < Mining.MINE_WORK_STAND_COUNT; slot++) {
            chunks.add(ChunkPos.containing(e.offset(-10 - slot, 0, 8)));
        }
        return Set.copyOf(chunks);
    }

    public static void releasePendingFixtureTickets(ServerLevel level) {
        if (level != null) {
            discardPendingPortalOccupant(level);
            releaseFixtureTickets(level);
        }
    }

    /** Mirrors the sealing target deduplication and distinguishes reused full blocks from paid placements. */
    private static MineSealCost mineSealCost(ServerLevel level, VillageData.Mine mine) {
        List<BlockPos> paid = new ArrayList<>();
        List<BlockPos> existingFull = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        Direction side = Direction.from2DDataValue(mine.dir).getClockWise();
        for (int lane = -1; lane <= 1; lane++) for (int y = 0; y < 3; y++) {
            BlockPos pos = mine.entrance.relative(side, lane).above(y);
            if (!seen.add(pos.asLong())) continue;
            BlockState state = level.getBlockState(pos);
            if (state.is(Blocks.SPRUCE_PLANKS) || state.isCollisionShapeFullBlock(level, pos)) existingFull.add(pos);
            else paid.add(pos);
        }
        Mining.MineSurfaceSealPlan surface = Mining.mineSurfaceSealPlan(level, mine);
        if (!surface.loaded()) throw new IllegalStateException("Mine seal cost fixture unexpectedly references unloaded surface cells");
        for (BlockPos pos : surface.coverCells()) addSealCostTarget(level, pos, seen, paid, existingFull);
        for (BlockPos pos : surface.roofCells()) addSealCostTarget(level, pos, seen, paid, existingFull);
        return new MineSealCost(List.copyOf(paid), List.copyOf(existingFull));
    }

    private static void addSealCostTarget(ServerLevel level, BlockPos pos, Set<Long> seen,
            List<BlockPos> paid, List<BlockPos> existingFull) {
        if (!seen.add(pos.asLong())) return;
        if (level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) existingFull.add(pos);
        else paid.add(pos);
    }

    private static void discardPendingPortalOccupant(ServerLevel level) {
        PendingPortalOccupant pending=PENDING_PORTAL_OCCUPANTS.remove(level);
        if(pending==null) return;
        net.minecraft.world.entity.Entity occupant=level.getEntity(pending.entity());
        if(occupant!=null) occupant.discard();
    }

    private static void releaseFixtureTickets(ServerLevel level) {
        FixtureLease lease = FIXTURE_TICKETS.remove(level);
        LAST_PREPARING_STATUS.remove(level);
        if (lease != null) releaseTickets(level, lease.chunks());
    }

    private static void releaseTickets(ServerLevel level, Set<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            level.getChunkSource().removeTicketWithRadius(Anchor.WALL_WORK, chunk, FIXTURE_TICKET_RADIUS);
        }
    }

    private static void check(boolean ok,String message) {
        if(!ok)throw new IllegalStateException("MINING SAFETY CHECK FAILED: "+message);
    }

    private static int containerCount(net.minecraft.world.Container container) {
        int total=0;
        for(int slot=0;slot<container.getContainerSize();slot++) total+=container.getItem(slot).getCount();
        return total;
    }

    private static String containerContents(net.minecraft.world.Container container) {
        java.util.List<String> contents=new java.util.ArrayList<>();
        for(int slot=0;slot<container.getContainerSize();slot++) {
            ItemStack stack=container.getItem(slot);
            if(!stack.isEmpty()) contents.add(slot+"="+stack);
        }
        return contents.toString();
    }

    private static String entrancePlanMismatches(ServerLevel level, java.util.List<Mining.MineEntranceBlock> plan) {
        java.util.List<String> mismatches=new java.util.ArrayList<>();
        for(Mining.MineEntranceBlock target:plan) {
            BlockState actual=level.getBlockState(target.pos());
            if(!actual.equals(target.state())) mismatches.add(target.pos().toShortString()+" expected="+target.state()+" actual="+actual);
        }
        return mismatches.toString();
    }

    private static void rememberAndClear(ServerLevel level, Map<BlockPos, Cell> before, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        before.putIfAbsent(pos, new Cell(level.getBlockState(pos), be == null ? null : be.saveWithFullMetadata(level.registryAccess())));
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
    }

    /** Uses actual opaque block geometry rather than cached skylight data in a synthetic fixture. */
    private static boolean hasDirectSkyOccluder(ServerLevel level, BlockPos openCell) {
        for (int y = openCell.getY() + 1; y < level.getMaxY(); y++) {
            BlockPos above = new BlockPos(openCell.getX(), y, openCell.getZ());
            if (level.getBlockState(above).isCollisionShapeFullBlock(level, above)) return true;
        }
        return false;
    }
}
