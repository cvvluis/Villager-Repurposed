package de.villagerplus.build;

import com.mojang.serialization.JsonOps;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobState;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageRef;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Actual navigation queries and builder transactions; no AI ticking or physical arrival is claimed. */
public final class BuilderStandChecks {
	private BuilderStandChecks() { }
	private static final int FLAGS = 2 | 16 | 32;
	private record Plan(String type, Optional<BlockPos> origin, int dir, int step, int variant,
		Optional<BlockPos> source, long progress, long nextBuild, List<String> wanted, Optional<VillageData.Extra.BuilderPlan> owner) {
		static Plan of(VillageData d) { return new Plan(d.planType,d.planOrigin,d.planDir,d.planStep,d.planVariant,
			d.planSource,d.planProgress,d.nextBuild,List.copyOf(d.wanted), copyOwner(d)); }
		void restore(VillageData d) {
			d.planType=type;d.planOrigin=origin;d.planDir=dir;d.planStep=step;d.planVariant=variant;
			d.planSource=source;d.planProgress=progress;d.nextBuild=nextBuild;d.wanted.clear();d.wanted.addAll(wanted);d.extra.planBuilder=owner;
		}
	}
	private static Optional<VillageData.Extra.BuilderPlan> copyOwner(VillageData d) { return d.extra.planBuilder.map(p -> new VillageData.Extra.BuilderPlan(p.owner, p.unavailableSince)); }

	@SuppressWarnings("unchecked")
	public static int run(ServerLevel level, VillageRef ref) throws Exception {
		VillageData d=ref.data(); Plan previous=Plan.of(d);
		Container box=ref.chest(level);
		if(box==null)throw new IllegalStateException("BUILDER STAND CHECK FAILED: fixture chest missing");
		List<ItemStack> stock=new ArrayList<>();for(int i=0;i<box.getContainerSize();i++)stock.add(box.getItem(i).copy());
		var oldLager=d.extra.lager;var oldMore=new ArrayList<>(d.extra.lagerMore);var oldChests=new ArrayList<>(d.extra.lagerChests);
		var oldDemolition=ref.bellEntity().getAttached(VillagerPlus.DEMOLITION);
		var oldConstruction=ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION);
		BlockPos floor=ref.bell().offset(65,0,65), origin=floor.above(13), preferred=floor.east(5);
		Map<BlockPos,BlockState> world=new LinkedHashMap<>();
		Map<String,Map<Long,Object>> caches=new LinkedHashMap<>();Map<String,Object> cacheValues=new LinkedHashMap<>();
		for(String name:List.of("STEPS","FOOT","PREP_RECORD","PALETTES")) {
			var field=Builder.class.getDeclaredField(name);field.setAccessible(true);
			Map<Long,Object> map=(Map<Long,Object>)field.get(null);caches.put(name,map);cacheValues.put(name,map.get(origin.asLong()));
		}
		Map<String,Object> stagedCacheValues=new LinkedHashMap<>();
		BlockPos stagedOrigin=floor.east(30).above(13);
		Villager worker=null,standBlocker=null;int passed=0;
		try {
			// Every touched column is on the runner's remote test plateau, and restored even after a failed assertion.
			for(int x=-8;x<=72;x++)for(int z=-8;z<=8;z++)for(int y=-1;y<=16;y++) {
				BlockPos p=floor.offset(x,y,z);level.getChunkAt(p);
				if(level.getBlockEntity(p)!=null)throw new IllegalStateException("BUILDER STAND CHECK FAILED: fixture overlaps block entity");
				world.put(p,level.getBlockState(p));level.setBlock(p,y==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),FLAGS);
			}
			level.setBlock(origin.below(),Blocks.STONE.defaultBlockState(),FLAGS);
			d.extra.lager=Optional.empty();d.extra.lagerMore.clear();d.extra.lagerChests.clear();
			ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			worker=EntityTypes.VILLAGER.spawn(level,preferred,EntitySpawnReason.COMMAND);
			if(worker==null)throw new IllegalStateException("BUILDER STAND CHECK FAILED: worker spawn");
			worker.addTag("vp090_fixture");worker.setNoAi(true);worker.setInvulnerable(true);
			Builder.clearBuildCrewSnapshotForCheck(level,ref);
			worker.getBrain().setMemory(MemoryModuleType.MEETING_POINT,GlobalPos.of(level.dimension(),ref.bell()));
			worker.setPos(preferred.getX()+0.5,preferred.getY(),preferred.getZ()+0.5);
			long now=Math.max(1,level.getGameTime());JobState st=new JobState();
			Ctx c=new Ctx(level,worker,st,worker.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now);
			// Repeated steps keep the test away from finish(), while still paying for exactly one real placement.
			List<Step> steps=java.util.Collections.nCopies(100,Step.block(origin,Blocks.COBBLESTONE));
			caches.get("STEPS").put(origin.asLong(),steps);caches.get("FOOT").remove(origin.asLong());caches.get("PREP_RECORD").remove(origin.asLong());
			d.planType=Blueprints.TOOLSHED;d.planOrigin=Optional.of(origin);d.planDir=Direction.EAST.get2DDataValue();
			d.planStep=0;d.planVariant=0;d.planSource=Optional.empty();d.planProgress=now;d.claimPlanBuilder(worker.getUUID());d.wanted.clear();
			box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,64));box.setItem(1,new ItemStack(Items.COBBLESTONE,64));
			BlockPos distant=floor.east(64);
			worker.setPos(distant.getX()+0.5,distant.getY(),distant.getZ()+0.5);worker.setOnGround(true);
			var defaultPath=worker.getNavigation().createPath(preferred,0);
			check(defaultPath==null||!defaultPath.canReach(),"default navigation cannot fully reach a stand 59 blocks away");passed++;
			check(preferred.equals(Builder.workStand(c,origin,Direction.EAST,steps)),
				"bounded construction search finds a real route from 59 blocks away");passed++;
			var cleanupProject = new VillageData.BuildProject("", origin, 0, 0, 0, Optional.empty(), now,
				Optional.of(worker.getUUID()), 0L, List.of(), VillageData.ProjectHaul.empty(), Optional.empty(),
				Optional.empty(), Optional.of(new VillageData.PlanBounds(origin.getX()-3,origin.getX()+3,
					origin.getZ()-3,origin.getZ()+3)), false, true);
			BlockPos cleanupStand = Builder.cleanupWorkStand(c,cleanupProject);
			var cleanupPath = cleanupStand == null ? null : Builder.workPath(c,cleanupStand);
			check(cleanupStand != null && !cleanupStand.equals(origin.above()) && Builder.safeWorkFloor(level,cleanupStand)
				&& cleanupPath != null && cleanupPath.canReach(),
				"abandoned-site refunds resolve to a safe reachable exterior stand instead of the block above the origin");passed++;
			worker.setPos(preferred.getX()+0.5,preferred.getY(),preferred.getZ()+0.5);
			var crewStandMethod = Builder.class.getDeclaredMethod("workStand",Ctx.class,BlockPos.class,Direction.class,List.class,int.class,int.class);
			crewStandMethod.setAccessible(true);
			BlockPos ownerStand = (BlockPos)crewStandMethod.invoke(null,c,origin,Direction.EAST,steps,Integer.MIN_VALUE,0);
			BlockPos firstHelperStand = (BlockPos)crewStandMethod.invoke(null,c,origin,Direction.EAST,steps,Integer.MIN_VALUE,1);
			BlockPos secondHelperStand = (BlockPos)crewStandMethod.invoke(null,c,origin,Direction.EAST,steps,Integer.MIN_VALUE,2);
			check(ownerStand != null && firstHelperStand != null && secondHelperStand != null
				&& horizontalDistanceSquared(ownerStand,firstHelperStand) >= 4
				&& horizontalDistanceSquared(ownerStand,secondHelperStand) >= 4
				&& horizontalDistanceSquared(firstHelperStand,secondHelperStand) >= 4,
				"owner and both helpers receive separate reachable work stands");passed++;
			standBlocker=EntityTypes.VILLAGER.spawn(level,firstHelperStand,EntitySpawnReason.COMMAND);
			if(standBlocker==null)throw new IllegalStateException("BUILDER STAND CHECK FAILED: stand blocker spawn");
			standBlocker.addTag("vp090_fixture");standBlocker.setNoAi(true);standBlocker.setInvulnerable(true);
			worker.setPos(distant.getX()+0.5,distant.getY(),distant.getZ()+0.5);
			BlockPos fallbackStand=(BlockPos)crewStandMethod.invoke(null,c,origin,Direction.EAST,steps,Integer.MIN_VALUE,1);
			var fallbackPath=fallbackStand==null?null:Builder.workPath(c,fallbackStand);
			check(fallbackStand!=null&&!fallbackStand.equals(firstHelperStand)
				&&Builder.safeWorkFloor(level,fallbackStand)&&fallbackPath!=null&&fallbackPath.canReach(),
				"a blocked helper slot falls back to a free, reachable stand instead of abandoning the crew task");passed++;
			standBlocker.discard();standBlocker=null;
			worker.setPos(distant.getX()+0.5,distant.getY(),distant.getZ()+0.5);
			var distantBefore=worker.position();Builder.tick(c,true);
			var walk=worker.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			check(walk.isPresent()&&d.planStep==0&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==128
				&&worker.position().equals(distantBefore)
				&&worker.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(walk.get().getTarget().currentBlockPosition()))<=100.0,
				"distant builder receives a short route waypoint without advancing, paying or teleporting"
					+ " [walk=" + walk.map(value -> value.getTarget().currentBlockPosition().toShortString()).orElse("none")
					+ ", step=" + d.planStep + ", cobble=" + JobUtil.count(box,s->s.is(Items.COBBLESTONE))
					+ ", moved=" + !worker.position().equals(distantBefore)
					+ ", distance=" + (walk.isPresent() ? worker.position().distanceToSqr(net.minecraft.world.phys.Vec3.atBottomCenterOf(walk.get().getTarget().currentBlockPosition())) : -1) + "]");passed++;
			var waypointPath=worker.getNavigation().createPath(walk.orElseThrow().getTarget().currentBlockPosition(),0);
			check(waypointPath!=null&&waypointPath.canReach(),"vanilla WALK_TARGET recomputation can reach the route waypoint");passed++;
			worker.getNavigation().stop();worker.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
			worker.setPos(preferred.getX()+0.5,preferred.getY(),preferred.getZ()+0.5);
			BlockPos stand=Builder.workStand(c,origin,Direction.EAST,steps);
			check(preferred.equals(stand)&&stand.getY()==origin.getY()-13&&Builder.safeWorkFloor(level,stand),
				"frontage uses supported local terrain thirteen blocks below the building anchor: stand="+stand+", expected="+preferred+", safe="+(stand!=null&&Builder.safeWorkFloor(level,stand)));passed++;
			BlockPos highStand=new BlockPos(origin.getX(),floor.getY()+1,origin.getZ()-3);
			level.setBlock(highStand.below(),Blocks.STONE.defaultBlockState(),FLAGS);
			worker.setPos(highStand.getX()+0.5,highStand.getY(),highStand.getZ()+0.5);
			BlockPos lowStand=new BlockPos(origin.getX()+5,floor.getY(),origin.getZ());
			check(lowStand.equals(Builder.workStand(c,origin,Direction.EAST,steps)),
				"fixture exposes a nearby low stand before the later elevated stand");passed++;
			level.setBlock(lowStand,Blocks.OAK_LEAVES.defaultBlockState(),FLAGS);
			BlockPos depthLimited=Builder.workStand(c,origin,Direction.EAST,steps,floor.getY()+1);
			check(highStand.equals(depthLimited)&&level.getBlockState(lowStand).is(Blocks.OAK_LEAVES),
				"depth limit skips the first low stand, selects the reachable higher stand and leaves rejected foliage intact");passed++;
			level.setBlock(lowStand,Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(highStand.below(),Blocks.AIR.defaultBlockState(),FLAGS);
			worker.setPos(preferred.getX()+0.5,preferred.getY(),preferred.getZ()+0.5);
			var before=worker.position();Builder.tick(c,true);
			check(d.planStep>0&&level.getBlockState(origin).is(Blocks.COBBLESTONE)
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==127&&worker.position().equals(before),
				"real builder tick advances on uneven frontage, pays one placement and does not teleport");passed++;

			level.setBlock(preferred.below(),Blocks.WATER.defaultBlockState(),FLAGS);
			BlockPos alternative=Builder.workStand(c,origin,Direction.EAST,steps);
			check(alternative!=null&&!alternative.equals(preferred)&&Builder.safeWorkFloor(level,alternative)
				&&!(alternative.getX()==origin.getX()&&alternative.getZ()==origin.getZ()),
				"wet preferred frontage falls back to a reachable clear perimeter position");passed++;
			level.setBlock(preferred.below(),Blocks.STONE.defaultBlockState(),FLAGS);
			BlockPos street = floor.west(3);
			level.setBlock(street.below(),Blocks.DIRT_PATH.defaultBlockState(),FLAGS);
			level.setBlock(floor.east(2).above(6),Blocks.STONE.defaultBlockState(),FLAGS);
			check(street.equals(Builder.villageReturn(level,floor.above(7))),
				"bell-tower return prefers the real street over a supported tower platform");passed++;
			BlockPos towerBell=floor.above(11);
			check(Builder.villageGroundY(level,towerBell)==floor.getY(),
				"eleven-block bell tower uses street height for depth limits");passed++;
			VillageRef towerRef=new VillageRef(towerBell,d,ref.bellEntity());
			worker.setPos(preferred.getX()+0.5,floor.getY()-2,preferred.getZ()+0.5);
			var slopePosition=worker.position();int relocations=st.builderRelocations;
			Builder.tick(new Ctx(level,worker,st,c.mem(),towerRef,now),true);
			check(st.builderRelocations==relocations&&worker.position().equals(slopePosition),
				"builder two blocks below street is not rescued relative to tower bell");passed++;
			worker.setPos(preferred.getX()+0.5,preferred.getY(),preferred.getZ()+0.5);
			level.setBlock(street.above(),Blocks.STONE.defaultBlockState(),FLAGS);
			check(!street.equals(Builder.villageReturn(level,floor.above(7))),
				"bell return rejects a street with blocked headroom");passed++;
			level.setBlock(street.above(),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(floor.east(2).above(6),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(preferred.above(),Blocks.STONE.defaultBlockState(),FLAGS);
			check(!Builder.safeWorkFloor(level,preferred),"head collision rejects a standing position");passed++;
			level.setBlock(preferred.above(),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(preferred.below(),Blocks.AIR.defaultBlockState(),FLAGS);
			check(!Builder.safeWorkFloor(level,preferred),"unsupported feet reject a standing position");passed++;

			for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++)
				level.setBlock(floor.offset(x,-1,z),Blocks.WATER.defaultBlockState(),FLAGS);
			check(Builder.villageReturn(level,floor.above(7))==null,
				"no dry arrival returns null instead of falling back onto the bell");passed++;
			d.planStep=0;int count=JobUtil.count(box,s->s.is(Items.COBBLESTONE));
			check(Builder.workStand(c,origin,Direction.EAST,steps)==null,"all wet perimeter candidates yield no work stand");passed++;
			level.setBlock(preferred.above(8),Blocks.STONE.defaultBlockState(),FLAGS);
			check(Builder.safeWorkFloor(level,preferred.above(9))&&Builder.workStand(c,origin,Direction.EAST,steps)==null,
				"a clear dry supported but unreachable high pillar is rejected by real navigation");passed++;
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+1),true);
			check(d.planStep==0&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==count&&worker.position().equals(before)
				&&st.cooldownUntil==now+41,"no safe stand preserves cursor, materials and worker position with bounded retry cooldown");passed++;
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+1302),true);
			check(!d.hasPlan()&&d.wanted.isEmpty()&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==count,
				"a persistently unreachable stand is rejected only after the bounded street-retry window");passed++;

			// A later shortage must not hold the first blocks hostage. The cursor and plan owner are durable,
			// while the wishlist shrinks to the remaining steps as paid/free work advances.
			List<Step> staged=List.of(
				Step.free(stagedOrigin.east(2).above(),Blocks.OAK_PLANKS.defaultBlockState()),
				Step.block(stagedOrigin.east(),Blocks.COBBLESTONE),
				Step.block(stagedOrigin.east(2),Blocks.OAK_PLANKS),
				Step.block(stagedOrigin.east(3),Blocks.STONE),
				Step.block(stagedOrigin.east(4),Blocks.GLASS_PANE));
			for(int dx=1;dx<=4;dx++)level.setBlock(stagedOrigin.east(dx).below(),Blocks.STONE.defaultBlockState(),FLAGS);
			caches.forEach((name,map)->stagedCacheValues.put(name,map.get(stagedOrigin.asLong())));
			caches.get("STEPS").put(stagedOrigin.asLong(),staged);
			BlockPos stagedFront=stagedOrigin.relative(Direction.EAST,7);
			worker.setPos(stagedFront.getX()+0.5,floor.getY(),stagedFront.getZ()+0.5);
			BlockPos stagedStand=Builder.workStand(c,stagedOrigin,Direction.EAST,staged);
			check(stagedStand!=null,"incremental material fixture has a reachable stand");passed++;
			worker.setPos(stagedStand.getX()+0.5,stagedStand.getY(),stagedStand.getZ()+0.5);
			d.planType=Blueprints.TOOLSHED;d.planOrigin=Optional.of(stagedOrigin);d.planDir=Direction.EAST.get2DDataValue();
			d.planStep=0;d.planVariant=0;d.planSource=Optional.empty();d.planProgress=now+2;d.claimPlanBuilder(worker.getUUID());
			d.wanted.clear();box.clearContent();
			box.setItem(0,new ItemStack(Items.COBBLESTONE,1));
			box.setItem(1,new ItemStack(Items.STONE,1));
			box.setItem(2,new ItemStack(Items.DIRT,64));
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+2),true);
			check(d.planStep==2&&level.getBlockState(stagedOrigin.east(2).above()).is(Blocks.OAK_PLANKS)
				&&level.getBlockState(stagedOrigin.east()).is(Blocks.COBBLESTONE)
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==0
				&&JobUtil.count(box,s->s.is(Items.STONE))==1&&JobUtil.count(box,s->s.is(Items.DIRT))==64,
				"free and stocked paid steps place before the first missing input; cursor stops there without consuming future or unrelated stock");passed++;
			check(d.wanted.size()==2&&d.wanted.contains("minecraft:oak_planks#1")
				&&d.wanted.contains("minecraft:glass_pane#1"),
				"wishlist contains only missing materials from the persisted cursor onward");passed++;
			var encodedPlan=VillageData.CODEC.encodeStart(JsonOps.INSTANCE,d).getOrThrow();
			VillageData reloadedPlan=VillageData.CODEC.parse(JsonOps.INSTANCE,encodedPlan).getOrThrow();
			check(reloadedPlan.planStep==2&&reloadedPlan.planBuilder().equals(Optional.of(worker.getUUID()))
				&&reloadedPlan.wanted.equals(d.wanted),
				"cursor, active builder owner, and remaining wishlist survive a plan codec reload");passed++;
			box.setItem(3,new ItemStack(Items.OAK_PLANKS,1));
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+3),true);
			check(d.planStep==4&&level.getBlockState(stagedOrigin.east(2)).is(Blocks.OAK_PLANKS)
				&&level.getBlockState(stagedOrigin.east(3)).is(Blocks.STONE)
				&&JobUtil.count(box,s->s.is(Items.OAK_PLANKS))==0&&JobUtil.count(box,s->s.is(Items.DIRT))==64
				&&d.wanted.size()==1&&d.wanted.contains("minecraft:glass_pane#1")
				&&d.planBuilder().equals(Optional.of(worker.getUUID())),
				"supplying the next block resumes the same owner at the saved cursor and refreshes demand after each payment");passed++;

			Palette supportPalette=new Palette(Blocks.STONE,Blocks.STONE,Blocks.OAK_PLANKS,Blocks.DIRT,Blocks.STONE,
				Blocks.STONE_STAIRS,Blocks.STONE_SLAB,Blocks.GLASS,Blocks.OAK_PLANKS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
			List<Step> supportSteps=java.util.Collections.nCopies(100,Step.block(stagedOrigin,Blocks.COBBLESTONE));
			caches.get("STEPS").put(stagedOrigin.asLong(),supportSteps);
			caches.get("PALETTES").put(stagedOrigin.asLong(),supportPalette);
			caches.get("FOOT").remove(stagedOrigin.asLong());caches.get("PREP_RECORD").remove(stagedOrigin.asLong());
			level.setBlock(stagedOrigin,Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(2),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(3),Blocks.STONE.defaultBlockState(),FLAGS);
			BlockPos supportStand=Builder.workStand(c,stagedOrigin,Direction.EAST,supportSteps);
			check(supportStand!=null,"incremental foundation fixture has a reachable stand");passed++;
			worker.setPos(supportStand.getX()+0.5,supportStand.getY(),supportStand.getZ()+0.5);
			d.planType=Blueprints.TOOLSHED;d.planOrigin=Optional.of(stagedOrigin);d.planDir=Direction.EAST.get2DDataValue();
			d.planStep=0;d.planVariant=0;d.planSource=Optional.empty();d.planProgress=now+4;d.claimPlanBuilder(worker.getUUID());
			d.wanted.clear();box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,1));box.setItem(1,new ItemStack(Items.DIRT,2));
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+4),true);
			check(d.planStep>0&&level.getBlockState(stagedOrigin).is(Blocks.COBBLESTONE)
				&&level.getBlockState(stagedOrigin.below()).is(Blocks.DIRT)&&level.getBlockState(stagedOrigin.below(2)).is(Blocks.DIRT)
				&&JobUtil.count(box,s->true)==0,
				"two support blocks and the main block each consume exactly one matching item");passed++;

			level.setBlock(stagedOrigin,Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(2),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(3),Blocks.STONE.defaultBlockState(),FLAGS);
			d.planStep=0;d.planProgress=now+5;d.wanted.clear();box.clearContent();
			box.setItem(0,new ItemStack(Items.COBBLESTONE,1));box.setItem(1,new ItemStack(Items.DIRT,1));
			worker.setPos(supportStand.getX()+0.5,supportStand.getY(),supportStand.getZ()+0.5);
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+5),true);
			check(d.planStep==0&&level.getBlockState(stagedOrigin).isAir()
				&&level.getBlockState(stagedOrigin.below()).isAir()&&level.getBlockState(stagedOrigin.below(2)).is(Blocks.DIRT)
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==1&&JobUtil.count(box,s->s.is(Items.DIRT))==0
				&&d.wanted.contains("minecraft:dirt#1")&&d.wanted.contains("minecraft:cobblestone#100"),
				"partial support supply fills bottom-up, preserves the main payment and cursor, and requests the remaining base item");passed++;
			box.setItem(1,new ItemStack(Items.DIRT,1));
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+6),true);
			check(d.planStep>0&&level.getBlockState(stagedOrigin).is(Blocks.COBBLESTONE)
				&&level.getBlockState(stagedOrigin.below()).is(Blocks.DIRT)&&level.getBlockState(stagedOrigin.below(2)).is(Blocks.DIRT)
				&&JobUtil.count(box,s->true)==0,
				"adding the missing support item resumes the same main step and consumes each material once");passed++;

			level.setBlock(stagedOrigin,Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(),Blocks.STONE.defaultBlockState(),FLAGS);
			d.planStep=0;d.planProgress=now+7;d.wanted.clear();box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,1));
			worker.setPos(supportStand.getX()+0.5,supportStand.getY(),supportStand.getZ()+0.5);
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+7),true);
			check(d.planStep>0&&level.getBlockState(stagedOrigin).is(Blocks.COBBLESTONE)
				&&level.getBlockState(stagedOrigin.below()).is(Blocks.STONE)&&JobUtil.count(box,s->true)==0,
				"existing solid support advances the main step without charging or replacing support");passed++;

			Palette cobbleBase=new Palette(Blocks.STONE,Blocks.STONE,Blocks.OAK_PLANKS,Blocks.COBBLESTONE,Blocks.STONE,
				Blocks.STONE_STAIRS,Blocks.STONE_SLAB,Blocks.GLASS,Blocks.OAK_PLANKS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
			caches.get("STEPS").put(stagedOrigin.asLong(),List.of(Step.block(stagedOrigin,Blocks.COBBLESTONE)));
			caches.get("PALETTES").put(stagedOrigin.asLong(),cobbleBase);
			level.setBlock(stagedOrigin,Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(2),Blocks.AIR.defaultBlockState(),FLAGS);
			level.setBlock(stagedOrigin.below(3),Blocks.STONE.defaultBlockState(),FLAGS);
			d.planStep=0;d.planProgress=now+8;d.wanted.clear();box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,2));
			worker.setPos(supportStand.getX()+0.5,supportStand.getY(),supportStand.getZ()+0.5);
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+8),true);
			check(d.planStep==0&&level.getBlockState(stagedOrigin).isAir()
				&&level.getBlockState(stagedOrigin.below()).is(Blocks.COBBLESTONE)
				&&level.getBlockState(stagedOrigin.below(2)).is(Blocks.COBBLESTONE)
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==0
				&&d.wanted.size()==1&&d.wanted.getFirst().equals("minecraft:cobblestone#1"),
				"wishlist merges same-item support and main-step needs, subtracts current stock, and requests only the unpaid main block");passed++;
			for(int depth=1;depth<=14;depth++)level.setBlock(stagedOrigin.below(depth),Blocks.AIR.defaultBlockState(),FLAGS);
			d.planStep=0;d.planProgress=now+9;d.wanted.clear();box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,1));
			worker.setPos(supportStand.getX()+0.5,supportStand.getY(),supportStand.getZ()+0.5);
			Builder.tick(new Ctx(level,worker,st,c.mem(),ref,now+9),true);
			check(!d.hasPlan()&&d.wanted.isEmpty()&&level.getBlockState(stagedOrigin).isAir()
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==1,
				"unanchored support rejects the plot without spending materials or leaving a plan in endless retry");passed++;

			BlockPos prepOrigin=stagedOrigin.east(10);
			BlockPos naturalLowGround=prepOrigin.below(14);
			level.setBlock(naturalLowGround,Blocks.DIRT.defaultBlockState(),FLAGS);
			List<Step> prepSteps=List.of(Step.block(prepOrigin,Blocks.COBBLESTONE));
			Map<Long,Integer> prepRecord=new LinkedHashMap<>();
			check(Prep.run(level,prepOrigin,prepSteps,48,Prep.footprint(prepSteps,prepOrigin),prepRecord)
				&&prepRecord.isEmpty()&&level.getBlockState(prepOrigin.below()).isAir()
				&&level.getBlockState(naturalLowGround).is(Blocks.DIRT),
				"terrain preparation leaves low natural ground untouched and records no free fill");passed++;
			long lowColumnKey=((long)prepOrigin.getX()<<32)|(prepOrigin.getZ()&0xFFFFFFFFL);
			Prep.restore(level,prepOrigin,Map.of(lowColumnKey,prepOrigin.getY()-13));
			check(level.getBlockState(naturalLowGround).is(Blocks.DIRT),
				"restoring a legacy low-column record does not alter natural soil below the build anchor");passed++;
			return passed;
		} finally {
			Builder.clearBuildCrewSnapshotForCheck(level,ref);
			if(standBlocker!=null){standBlocker.discard();de.villagerplus.village.Villages.forget(standBlocker.getUUID());}
			if(worker!=null){Builder.clearCrewRuntimeCachesForCheck(level,worker.getUUID());worker.discard();de.villagerplus.village.Villages.forget(worker.getUUID());}
			world.forEach((p,state)->level.setBlock(p,state,FLAGS));
			box.clearContent();for(int i=0;i<stock.size();i++)box.setItem(i,stock.get(i));
			previous.restore(d);d.extra.lager=oldLager;d.extra.lagerMore.clear();d.extra.lagerMore.addAll(oldMore);
			d.extra.lagerChests.clear();d.extra.lagerChests.addAll(oldChests);
			if(oldDemolition==null)ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);else ref.bellEntity().setAttached(VillagerPlus.DEMOLITION,oldDemolition);
			if(oldConstruction==null)ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION,oldConstruction);
			caches.forEach((name,map)->{
				Object old=cacheValues.get(name);if(old==null)map.remove(origin.asLong());else map.put(origin.asLong(),old);
				Object stagedOld=stagedCacheValues.get(name);if(stagedOld==null)map.remove(stagedOrigin.asLong());else map.put(stagedOrigin.asLong(),stagedOld);
			});
			ref.dirty();
		}
	}
	private static void check(boolean condition,String message) {
		if(!condition)throw new IllegalStateException("BUILDER STAND CHECK FAILED: "+message);
	}
	private static int horizontalDistanceSquared(BlockPos a,BlockPos b) {
		int dx=a.getX()-b.getX(), dz=a.getZ()-b.getZ();
		return dx*dx+dz*dz;
	}
}
