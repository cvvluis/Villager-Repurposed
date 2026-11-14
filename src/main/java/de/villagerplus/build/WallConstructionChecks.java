package de.villagerplus.build;

import de.villagerplus.VillagerPlus;
import de.villagerplus.Config;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobUtil;
import de.villagerplus.village.Anchor;
import de.villagerplus.village.Development;
import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageExtent;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.VillageStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/** Real builder transactions on the development runner's remote plateau, with all touched fixture state restored. */
public final class WallConstructionChecks {
	private WallConstructionChecks() { }
	private static final int FLAGS = 2 | 16 | 32;
	private record Plan(String type, Optional<BlockPos> origin, int dir, int step, int variant, Optional<BlockPos> source,
		long progress, long nextBuild, List<String> wanted, boolean wallBuilt, Optional<VillageData.Extra.BuilderPlan> owner,
		Optional<String> planMaterialWait) {
		static Plan of(VillageData d) { return new Plan(d.planType,d.planOrigin,d.planDir,d.planStep,d.planVariant,d.planSource,d.planProgress,d.nextBuild,List.copyOf(d.wanted),d.wallBuilt,d.extra.planBuilder.map(p -> new VillageData.Extra.BuilderPlan(p.owner,p.unavailableSince)),d.extra.planMaterialWait); }
		void restore(VillageData d) { d.planType=type;d.planOrigin=origin;d.planDir=dir;d.planStep=step;d.planVariant=variant;d.planSource=source;d.planProgress=progress;d.nextBuild=nextBuild;d.wanted.clear();d.wanted.addAll(wanted);d.wallBuilt=wallBuilt;d.extra.planBuilder=owner;d.extra.planMaterialWait=planMaterialWait; }
	}

	@SuppressWarnings("unchecked")
	public static int run(ServerLevel level, VillageRef ref) throws Exception {
		int passed = 0, radius = 40, cursor = 10;
		VillageData d = ref.data(); Plan oldPlan = Plan.of(d);
		var profiles = ref.bellEntity().getAttached(Wall.PROFILES);
		var extent = ref.bellEntity().getAttached(VillagerPlus.VILLAGE_EXTENT);
		var buildings = ref.bellEntity().getAttached(VillagerPlus.BUILDINGS);
		var demolition = ref.bellEntity().getAttached(VillagerPlus.DEMOLITION);
		var construction = ref.bellEntity().getAttached(VillagerPlus.CONSTRUCTION);
		var oldLager=d.extra.lager;var oldMore=new ArrayList<>(d.extra.lagerMore);var oldChests=new ArrayList<>(d.extra.lagerChests);
		Container box=ref.chest(level);if(box==null)throw new IllegalStateException("WALL CHECK FAILED: missing fixture chest");
		List<ItemStack> stock=new ArrayList<>();for(int i=0;i<box.getContainerSize();i++)stock.add(box.getItem(i).copy());
		Map<BlockPos,BlockState> world=new LinkedHashMap<>();
		var styleField=Builder.class.getDeclaredField("WALL_STYLES");styleField.setAccessible(true);
		Map<Long,VillageStyle> styles=(Map<Long,VillageStyle>)styleField.get(null);VillageStyle oldStyle=styles.get(ref.bell().asLong());
		VillageStyle style=new VillageStyle(Blocks.COBBLESTONE,Blocks.OAK_PLANKS,Blocks.OAK_PLANKS,Blocks.COBBLESTONE_STAIRS,Blocks.OAK_DOOR,Blocks.OAK_FENCE);
		Villager worker=null;
		try {
			ref.bellEntity().setAttached(Wall.PROFILES,Map.of());
			ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);
			d.extra.lager=Optional.empty();d.extra.lagerMore.clear();d.extra.lagerChests.clear();styles.put(ref.bell().asLong(),style);
			int[] first=Wall.ringOffset(radius,cursor);
			for(int t=cursor;t<cursor+2;t++) {
				int[] offset=Wall.ringOffset(radius,t);var in=Wall.inward(radius,t);
				for(int kk=0;kk<9;kk++) {
					int x=ref.bell().getX()+offset[0]+in.getStepX()*kk,z=ref.bell().getZ()+offset[1]+in.getStepZ()*kk;
					for(int y=-4;y<=13;y++) {
						BlockPos pos=new BlockPos(x,ref.bell().getY()+y,z);level.getChunkAt(pos);world.putIfAbsent(pos,level.getBlockState(pos));
						level.setBlock(pos,y==-4?Blocks.STONE.defaultBlockState():y<0?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),FLAGS);
					}
				}
			}
			var route=Anchor.wallWorkChunks(ref.bell(),ref.bell(),Development.MAX_WALL_RADIUS,0);
			int[] outer=Wall.ringOffset(Development.MAX_WALL_RADIUS,0);
			check(route.contains(new ChunkPos((ref.bell().getX()+outer[0])>>4,(ref.bell().getZ()+outer[1])>>4))
				&&route.size()<=53&&Anchor.WALL_WORK.doesLoad()&&Anchor.WALL_WORK.doesSimulate()&&!Anchor.WALL_WORK.persist()
				&&Anchor.WALL_WORK.timeout()==240L&&BuiltInRegistries.TICKET_TYPE.getKey(Anchor.WALL_WORK).getNamespace().equals("villagerplus"),
				"outer wall work has a bounded registered loading/simulation corridor with expiring tickets");passed++;
			List<Wall.Cell> a=Wall.column(level,ref.bell(),style.wall(),style.stairs(),style.fence(),radius,cursor);
			List<Wall.Cell> b=Wall.column(level,ref.bell(),style.wall(),style.stairs(),style.fence(),radius,cursor+1);
			check(!a.isEmpty()&&a.stream().anyMatch(cell->cell.paid()&&cell.pos().getY()<ref.bell().getY())
				&&!Builder.columnIntact(level,ref.bell(),style,radius,cursor),"water produces paid foundations down to solid bed and an unbuilt column is incomplete");passed++;
			var saved=ref.bellEntity().saveWithFullMetadata(level.registryAccess());
			BlockEntity restored=BlockEntity.loadStatic(ref.bell(),level.getBlockState(ref.bell()),saved,level.registryAccess());
			check(restored!=null&&restored.getAttachedOrElse(Wall.PROFILES,Map.of()).equals(ref.bellEntity().getAttached(Wall.PROFILES)),
				"fixed wall column heights and footings survive block-entity save/load");passed++;
			var in=Wall.inward(radius,cursor);
			int standX=ref.bell().getX()+first[0]+in.getStepX()*11;
			int standZ=ref.bell().getZ()+first[1]+in.getStepZ()*11;
			setFixtureStand(level,world,ref.bell().east());
			setFixtureStand(level,world,new BlockPos(standX,ref.bell().getY(),standZ));
			BlockPos stand=new BlockPos(standX,Wall.groundHeight(level,standX,standZ,style.wall()),standZ);
			worker=EntityTypes.VILLAGER.spawn(level,stand,EntitySpawnReason.COMMAND);if(worker==null)throw new IllegalStateException("WALL CHECK FAILED: worker spawn");
			worker.addTag("vp090_fixture");worker.setNoAi(true);worker.setInvulnerable(true);
			de.villagerplus.village.Villages.bindResident(level,ref,worker,null);
			box.clearContent();for(int i=0;i<3;i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));
			long now=level.getGameTime()+1;d.planType=Blueprints.WALL;d.planOrigin=Optional.of(ref.bell());d.planVariant=radius;d.planStep=cursor;d.planProgress=now;
			d.wanted.clear();d.claimPlanBuilder(worker.getUUID());
			Ctx c=new Ctx(level,worker,JobManager.state(worker),worker.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now);
			int cost=(int)(a.stream().filter(Wall.Cell::paid).count()+b.stream().filter(Wall.Cell::paid).count());
			VillageRef assigned=de.villagerplus.village.Villages.find(level,worker);
			check(d.planBuilder().filter(worker.getUUID()::equals).isPresent()&&assigned!=null&&assigned.bell().equals(ref.bell()),
				"paid wall transaction fixture is bound to its assigned plan owner");passed++;
			int bellY=ref.bell().getY();
			int villageGroundY=Builder.villageGroundY(level,ref.bell());
			BlockPos villageReturn=Builder.villageReturn(level,ref.bell());
			boolean planBefore=d.hasPlan();String planTypeBefore=d.planType;var ownerBefore=d.planBuilder();
			boolean combinedAvailable=de.villagerplus.village.Lager.combined(level,ref)!=null;
			int demolitionJobs=de.villagerplus.village.Demolition.jobs(ref).size();
			boolean ownerAlive=worker.isAlive(),ownerBaby=worker.isBaby(),ownerGuest=de.villagerplus.village.Guests.isGuest(worker);
			boolean ownerAway=de.villagerplus.village.Expedition.isAway(worker),ownerPassenger=de.villagerplus.village.RailTransport.isPassenger(worker);
			boolean ownerHasShop=de.villagerplus.village.Market.shop(ref,worker.getUUID())!=null;
			String assignedVillage=assigned==null?"null":assigned.bell().toShortString();
			boolean tickResult=Builder.tick(c,true);
			boolean firstFinished=Builder.columnIntact(level,ref.bell(),style,radius,cursor);
			boolean secondFinished=Builder.columnIntact(level,ref.bell(),style,radius,cursor+1);
			int remainingStone=JobUtil.count(box,s->s.is(Items.COBBLESTONE));
			check(d.planStep==cursor+2&&firstFinished&&secondFinished&&remainingStone==192-cost,
				"real builder tick finishes two water-supported columns and pays exactly their new wall blocks",
				"step="+d.planStep+", complete="+firstFinished+"/"+secondFinished+", stock="+remainingStone+" expected="+(192-cost)
				+", cost="+cost+", wanted="+d.wanted.size()+", activity="+c.st().activity+", tickResult="+tickResult
				+", bellY="+bellY+", groundY="+villageGroundY+", villageReturn="+(villageReturn==null?"null":villageReturn.toShortString())
				+", planBefore="+planBefore+"/"+planTypeBefore+"/"+ownerBefore+", planAfter="+d.hasPlan()+"/"+d.planType+"/"+d.planBuilder()
				+", combined="+combinedAvailable+", demolitionJobs="+demolitionJobs+", ownerAlive="+ownerAlive+", baby="+ownerBaby
				+", guest="+ownerGuest+", away="+ownerAway+", passenger="+ownerPassenger+", hasShop="+ownerHasShop+", assigned="+assignedVillage
				+", worker="+worker.blockPosition().toShortString()+", uuid="+worker.getUUID());passed++;
			check(a.equals(Wall.column(level,ref.bell(),style.wall(),style.stairs(),style.fence(),radius,cursor)),
				"column geometry stays fixed after foundations replace water and raise the heightmap");passed++;
			Wall.Cell changed=a.stream().filter(Wall.Cell::paid).findFirst().orElseThrow();level.setBlock(changed.pos(),Blocks.DIAMOND_BLOCK.defaultBlockState(),FLAGS);
			check(!Builder.columnIntact(level,ref.bell(),style,radius,cursor),"foreign solid blocks cannot masquerade as completed wall material");passed++;
			int remaining=JobUtil.count(box,s->s.is(Items.COBBLESTONE));d.planStep=cursor;d.planProgress=now+100;
			Builder.tick(new Ctx(level,worker,c.st(),c.mem(),ref,now+100),true);
			check(d.planStep==cursor&&level.getBlockState(changed.pos()).is(Blocks.DIAMOND_BLOCK)&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==remaining,
				"a blocked column preserves foreign property, the cursor and the material stock");passed++;
			level.setBlock(changed.pos(),Blocks.AIR.defaultBlockState(),FLAGS);
			check(!Builder.columnIntact(level,ref.bell(),style,radius,cursor)&&!Builder.columnIntact(level,ref.bell(),style,0,0),
				"missing cells and unavailable empty column plans remain incomplete");passed++;
			ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT,new VillageExtent(ref.bell().getX()-80,ref.bell().getY(),ref.bell().getZ()-70,ref.bell().getX()+70,ref.bell().getY()+12,ref.bell().getZ()+80));
			ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,List.of(
				new Built(Blueprints.HOUSE,ref.bell().east(110),0,64<<2,List.of()),
				new Built(Blueprints.HOUSE,ref.bell().west(110),0,64<<2,List.of()),
				new Built(Blueprints.HOUSE,ref.bell().north(110),0,64<<2,List.of()),
				new Built(Blueprints.HOUSE,ref.bell().south(110),0,64<<2,List.of())));
			var bounds=Development.bounds(level,ref);int needed=Development.neededWallRadius(level,ref);
			int edge=Math.max(Math.max(Math.abs(bounds.minX()-ref.bell().getX()),Math.abs(bounds.maxX()-ref.bell().getX())),
				Math.max(Math.abs(bounds.minZ()-ref.bell().getZ()),Math.abs(bounds.maxZ()-ref.bell().getZ())));
			boolean ringOutside=true;
			for(int t=0;t<8*needed;t++){
				int[] offset=Wall.ringOffset(needed,t);int x=ref.bell().getX()+offset[0],z=ref.bell().getZ()+offset[1];
				if(x>=bounds.minX()&&x<=bounds.maxX()&&z>=bounds.minZ()&&z<=bounds.maxZ()){ringOutside=false;break;}
			}
			check(bounds.minX()<ref.bell().getX()-110&&bounds.maxX()>ref.bell().getX()+110
				&&bounds.minZ()<ref.bell().getZ()-110&&bounds.maxZ()>ref.bell().getZ()+110
				&&needed-edge>=14&&needed<=Development.MAX_WALL_RADIUS&&ringOutside,
				"wall radius contains remembered original district and all four outer additions with a clear ring");passed++;
			passed+=checkGrowthExtension(level,ref,box,world,worker,style);
			return passed;
		} finally {
			if(worker!=null){worker.discard();de.villagerplus.village.Villages.forget(worker.getUUID());}
			world.forEach((pos,state)->level.setBlock(pos,state,FLAGS));
			box.clearContent();for(int i=0;i<stock.size();i++)box.setItem(i,stock.get(i));
			oldPlan.restore(d);d.extra.lager=oldLager;d.extra.lagerMore.clear();d.extra.lagerMore.addAll(oldMore);d.extra.lagerChests.clear();d.extra.lagerChests.addAll(oldChests);
			if(oldStyle==null)styles.remove(ref.bell().asLong());else styles.put(ref.bell().asLong(),oldStyle);
			if(profiles==null)ref.bellEntity().removeAttached(Wall.PROFILES);else ref.bellEntity().setAttached(Wall.PROFILES,profiles);
			if(extent==null)ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT,extent);
			if(buildings==null)ref.bellEntity().removeAttached(VillagerPlus.BUILDINGS);else ref.bellEntity().setAttached(VillagerPlus.BUILDINGS,buildings);
			if(demolition==null)ref.bellEntity().removeAttached(VillagerPlus.DEMOLITION);else ref.bellEntity().setAttached(VillagerPlus.DEMOLITION,demolition);
			if(construction==null)ref.bellEntity().removeAttached(VillagerPlus.CONSTRUCTION);else ref.bellEntity().setAttached(VillagerPlus.CONSTRUCTION,construction);
			ref.dirty();
		}
	}

	@SuppressWarnings("unchecked")
	private static int checkGrowthExtension(ServerLevel level,VillageRef ref,Container box,Map<BlockPos,BlockState> world,
		Villager worker,VillageStyle style) throws Exception {
		int passed=0;
		var bell=ref.bell();
		var manager=level.getPoiManager();
		var previousBellPoi=manager.getType(bell).orElse(null);
		boolean injectMeeting=previousBellPoi==null||!previousBellPoi.is(PoiTypes.MEETING);
		BlockPos exteriorPoi=null;
		boolean poiAdded=false;
		var checkedField=Builder.class.getDeclaredField("WALL_CHECKED");checkedField.setAccessible(true);
		Map<Long,Long> checked=(Map<Long,Long>)checkedField.get(null);
		Long previousCheck=checked.remove(bell.asLong());
		try {
			if(injectMeeting){
				if(previousBellPoi!=null)manager.remove(bell);
				manager.add(bell,BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.MEETING));
			}
			var oldBounds=Development.bounds(level,ref);
			int oldEdge=Math.max(Math.max(Math.abs(oldBounds.minX()-bell.getX()),Math.abs(oldBounds.maxX()-bell.getX())),
				Math.max(Math.abs(oldBounds.minZ()-bell.getZ()),Math.abs(oldBounds.maxZ()-bell.getZ())));
			int oldRadius=Development.neededWallRadius(level,ref);
			check(oldRadius>=Config.WALL_MIN_VILLAGE_RADIUS&&oldRadius<=Development.MAX_WALL_RADIUS,
				"growth fixture starts from a bounded completed-wall radius");passed++;

			// The persistent profile manifest marks the completed old ring even when its chunks are unloaded.
			int baseY=Math.max(level.getMinY()+4,Math.min(bell.getY(),level.getMaxY()-12));
			List<Integer> footings=java.util.Collections.nCopies(Wall.THICK+6,baseY-1);
			Map<String,Wall.Profile> oldProfiles=new LinkedHashMap<>();
			for(int t=0;t<8*oldRadius;t++)oldProfiles.put(oldRadius+":"+t,new Wall.Profile(baseY,footings));
			ref.bellEntity().setAttached(Wall.PROFILES,Map.copyOf(oldProfiles));
			ref.data().clearPlan();ref.data().wallBuilt=true;ref.data().wanted.clear();
			int oldT=Math.min(4,8*oldRadius-1);
			int[] oldOffset=Wall.ringOffset(oldRadius,oldT);var oldIn=Wall.inward(oldRadius,oldT);
			for(int k=0;k<Wall.THICK+6;k++)level.getChunkAt(new BlockPos(bell.getX()+oldOffset[0]+oldIn.getStepX()*k,bell.getY(),bell.getZ()+oldOffset[1]+oldIn.getStepZ()*k));
			List<Wall.Cell> oldCells=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),oldRadius,oldT);
			if(oldCells.isEmpty())throw new IllegalStateException("WALL CHECK FAILED: old perimeter fixture unavailable");
			for(Wall.Cell cell:oldCells){world.putIfAbsent(cell.pos(),level.getBlockState(cell.pos()));level.setBlock(cell.pos(),cell.state(),FLAGS);}
			check(Builder.columnIntact(level,bell,style,oldRadius,oldT),
				"completed initial perimeter has a persisted outer-radius manifest and a real intact wall column");passed++;
			var detect=Builder.class.getDeclaredMethod("detectWallRadius",ServerLevel.class,BlockPos.class,VillageStyle.class);detect.setAccessible(true);
			check((int)detect.invoke(null,level,bell,style)==oldRadius,
				"completed wall radius is recoverable from saved profiles when its outer chunks unload");passed++;

			int poiOffset=oldEdge+24;
			if(poiOffset+14>Development.MAX_WALL_RADIUS)
				throw new IllegalStateException("WALL CHECK FAILED: fixture bounds leave no safe room for an exterior POI");
			var meetings=manager.getInRange(h->h.is(PoiTypes.MEETING),bell,Development.MEETING_POI_DISCOVERY_RADIUS,
				PoiManager.Occupancy.ANY).map(p->p.getPos().immutable()).toList();
			for(BlockPos candidate:List.of(bell.east(poiOffset),bell.west(poiOffset),bell.south(poiOffset),bell.north(poiOffset))){
				if(manager.getType(candidate).isPresent())continue;
				double nearestOther=meetings.stream().filter(point->!point.equals(bell)).mapToDouble(point->point.distSqr(candidate)).min().orElse(Double.MAX_VALUE);
				if(candidate.distSqr(bell)<nearestOther){exteriorPoi=candidate;break;}
			}
			if(exteriorPoi==null)throw new IllegalStateException("WALL CHECK FAILED: no exterior POI spot belongs unambiguously to this bell");
			level.getChunkAt(exteriorPoi);
			manager.add(exteriorPoi,BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME));poiAdded=true;
			var expandedBounds=Development.bounds(level,ref);
			int expandedRadius=Development.neededWallRadius(level,ref);
			int expandedEdge=Math.max(Math.max(Math.abs(expandedBounds.minX()-bell.getX()),Math.abs(expandedBounds.maxX()-bell.getX())),
				Math.max(Math.abs(expandedBounds.minZ()-bell.getZ()),Math.abs(expandedBounds.maxZ()-bell.getZ())));
			boolean ringOutside=true;
			for(int t=0;t<8*expandedRadius;t++){
				int[] offset=Wall.ringOffset(expandedRadius,t);int x=bell.getX()+offset[0],z=bell.getZ()+offset[1];
				if(x>=expandedBounds.minX()&&x<=expandedBounds.maxX()&&z>=expandedBounds.minZ()&&z<=expandedBounds.maxZ()){ringOutside=false;break;}
			}
			boolean outsideOld=exteriorPoi.getX()<oldBounds.minX()||exteriorPoi.getX()>oldBounds.maxX()
				||exteriorPoi.getZ()<oldBounds.minZ()||exteriorPoi.getZ()>oldBounds.maxZ();
			check(poiOffset>Config.VILLAGE_RADIUS+16&&outsideOld&&exteriorPoi.getX()>=expandedBounds.minX()&&exteriorPoi.getX()<=expandedBounds.maxX()
				&&exteriorPoi.getZ()>=expandedBounds.minZ()&&exteriorPoi.getZ()<=expandedBounds.maxZ()
				&&expandedRadius>oldRadius+4&&expandedRadius<=Development.MAX_WALL_RADIUS&&expandedRadius-expandedEdge>=14&&ringOutside,
				"a newly recognized exterior HOME POI refreshes the saved bounds and moves the full perimeter outside them");passed++;
			passed+=checkDiagonalHomeDiscovery(level,ref);
			passed+=checkNeighborHomeAttribution(level,ref);

			var tooSmall=Builder.class.getDeclaredMethod("wallTooSmall",ServerLevel.class,VillageRef.class);tooSmall.setAccessible(true);
			check((boolean)tooSmall.invoke(null,level,ref),"completed automatic wall is marked for growth after the exterior POI is discovered");passed++;
			box.clearContent();for(int i=0;i<3;i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));
			long now=level.getGameTime()+200;
			Ctx c=new Ctx(level,worker,JobManager.state(worker),worker.getAttachedOrCreate(VillagerPlus.MEMORY),ref,now);
			var start=Builder.class.getDeclaredMethod("startWall",Ctx.class,VillageRef.class,Container.class,List.class);start.setAccessible(true);
			check((boolean)start.invoke(null,c,ref,box,List.of(worker))&&ref.data().planType.equals(Blueprints.WALL)
				&&ref.data().planVariant==expandedRadius&&ref.data().planStep==0&&ref.data().wallBuilt,
				"growth starts a persisted outer-ring cursor while the completed inner wall remains built");passed++;

			for(int t=0;t<6;t++){
				int[] offset=Wall.ringOffset(expandedRadius,t);var inward=Wall.inward(expandedRadius,t);
				for(int k=0;k<Wall.THICK+6;k++){
					int x=bell.getX()+offset[0]+inward.getStepX()*k,z=bell.getZ()+offset[1]+inward.getStepZ()*k;
					for(int y=-4;y<=13;y++){
						BlockPos pos=new BlockPos(x,bell.getY()+y,z);level.getChunkAt(pos);world.putIfAbsent(pos,level.getBlockState(pos));
						level.setBlock(pos,y==-4?Blocks.STONE.defaultBlockState():y<0?Blocks.WATER.defaultBlockState():Blocks.AIR.defaultBlockState(),FLAGS);
					}
				}
			}
			List<Wall.Cell> first=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),expandedRadius,0);
			List<Wall.Cell> second=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),expandedRadius,1);
			int cost=(int)(first.stream().filter(Wall.Cell::paid).count()+second.stream().filter(Wall.Cell::paid).count());
			int before=JobUtil.count(box,s->s.is(Items.COBBLESTONE));
			int[] firstOffset=Wall.ringOffset(expandedRadius,0);var firstIn=Wall.inward(expandedRadius,0);
			int sx=bell.getX()+firstOffset[0]+firstIn.getStepX()*11,sz=bell.getZ()+firstOffset[1]+firstIn.getStepZ()*11;
			setFixtureStand(level,world,new BlockPos(sx,bell.getY(),sz));
			int sy=Wall.groundHeight(level,sx,sz,style.wall());worker.teleportTo(sx+0.5,sy,sz+0.5);
			boolean extensionResult=Builder.tick(new Ctx(level,worker,c.st(),c.mem(),ref,now),true);
			boolean outer0Complete=Builder.columnIntact(level,bell,style,expandedRadius,0);
			boolean outer1Complete=Builder.columnIntact(level,bell,style,expandedRadius,1);
			boolean oldWallComplete=Builder.columnIntact(level,bell,style,oldRadius,oldT);
			int extensionStock=JobUtil.count(box,s->s.is(Items.COBBLESTONE));
			int extensionGroundY=Builder.villageGroundY(level,bell);BlockPos extensionReturn=Builder.villageReturn(level,bell);
			check(ref.data().planStep==2&&outer0Complete&&outer1Complete&&extensionStock==before-cost&&oldWallComplete,
				"real extension ticks pay exact wall material incrementally and preserve the old wall blocks",
				"step="+ref.data().planStep+" expected=2, outer="+outer0Complete+"/"+outer1Complete+", stock="+extensionStock
				+" expected="+(before-cost)+", oldWall="+oldWallComplete+", groundY="+extensionGroundY
				+", villageReturn="+(extensionReturn==null?"null":extensionReturn.toShortString())+", worker="+worker.blockPosition().toShortString()
				+", tickResult="+extensionResult+", activity="+c.st().activity+", wanted="+ref.data().wanted);passed++;

			int partialStep=ref.data().planStep;
			List<Wall.Cell> partialCells=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),expandedRadius,partialStep);
			int partialCost=(int)partialCells.stream().filter(Wall.Cell::paid).count();
			if(partialCost<=3)throw new IllegalStateException("WALL CHECK FAILED: partial outer column costs three or fewer wall blocks");
			int[] partialOffset=Wall.ringOffset(expandedRadius,partialStep);var partialIn=Wall.inward(expandedRadius,partialStep);
			int partialX=bell.getX()+partialOffset[0]+partialIn.getStepX()*11,partialZ=bell.getZ()+partialOffset[1]+partialIn.getStepZ()*11;
			setFixtureStand(level,world,new BlockPos(partialX,bell.getY(),partialZ));
			int partialY=Wall.groundHeight(level,partialX,partialZ,style.wall());worker.teleportTo(partialX+0.5,partialY,partialZ+0.5);
			box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,3));
			boolean shortageResult=Builder.tick(new Ctx(level,worker,c.st(),c.mem(),ref,now+200),true);
			long paidInPartial=partialCells.stream().filter(Wall.Cell::paid)
				.filter(cell->Wall.matches(cell,level.getBlockState(cell.pos()))).count();
			check(!shortageResult&&ref.data().planStep==partialStep&&paidInPartial==3&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==0
				&&ref.data().wanted.stream().anyMatch(w->w.startsWith("minecraft:cobblestone#")),
				"wall material shortage yields to the supplier cycle without advancing a partly paid column");passed++;
			List<Wall.Cell> followingCells=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),expandedRadius,partialStep+1);
			int supplierCost=(int)(partialCost-3+followingCells.stream().filter(Wall.Cell::paid).count());
			box.clearContent();for(int i=0;i<3;i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));
			int supplierStock=JobUtil.count(box,s->s.is(Items.COBBLESTONE));
			setFixtureStand(level,world,new BlockPos(partialX,bell.getY(),partialZ));
			boolean supplierResume=Builder.tick(new Ctx(level,worker,c.st(),c.mem(),ref,now+400),true);
			check(supplierResume&&ref.data().planStep==partialStep+2&&Builder.columnIntact(level,bell,style,expandedRadius,partialStep)
				&&Builder.columnIntact(level,bell,style,expandedRadius,partialStep+1)&&Builder.columnIntact(level,bell,style,oldRadius,oldT)
				&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==supplierStock-supplierCost,
				"a resumed wall plan continues after supplier delivery and charges only the remaining two columns");passed++;

			int cancelStep=ref.data().planStep;
			List<Wall.Cell> cancelCells=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),expandedRadius,cancelStep);
			int[] cancelOffset=Wall.ringOffset(expandedRadius,cancelStep);var cancelIn=Wall.inward(expandedRadius,cancelStep);
			int cancelX=bell.getX()+cancelOffset[0]+cancelIn.getStepX()*11,cancelZ=bell.getZ()+cancelOffset[1]+cancelIn.getStepZ()*11;
			setFixtureStand(level,world,new BlockPos(cancelX,bell.getY(),cancelZ));
			int cancelY=Wall.groundHeight(level,cancelX,cancelZ,style.wall());worker.teleportTo(cancelX+0.5,cancelY,cancelZ+0.5);
			box.clearContent();box.setItem(0,new ItemStack(Items.COBBLESTONE,3));
			boolean cancelShortage=Builder.tick(new Ctx(level,worker,c.st(),c.mem(),ref,now+600),true);
			long paidBeforeCancel=cancelCells.stream().filter(Wall.Cell::paid)
				.filter(cell->Wall.matches(cell,level.getBlockState(cell.pos()))).count();
			check(!cancelShortage&&ref.data().planStep==cancelStep&&paidBeforeCancel==3
				&&ref.data().wanted.stream().anyMatch(w->w.startsWith("minecraft:cobblestone#")),
				"a later shortage holds the advanced cursor on its partly paid outer column");passed++;

			var saved=ref.bellEntity().saveWithFullMetadata(level.registryAccess());
			BlockEntity restored=BlockEntity.loadStatic(bell,level.getBlockState(bell),saved,level.registryAccess());
			VillageData restoredData=restored==null?null:restored.getAttached(VillagerPlus.VILLAGE);
			Map<String,Wall.Profile> restoredProfiles=restored==null?Map.of():restored.getAttachedOrElse(Wall.PROFILES,Map.of());
			check(restoredData!=null&&restoredData.hasPlan()&&restoredData.planType.equals(Blueprints.WALL)
				&&restoredData.planVariant==expandedRadius&&restoredData.planStep==cancelStep&&restoredData.wallBuilt
				&&restoredData.wanted.equals(ref.data().wanted)&&restoredProfiles.containsKey(oldRadius+":"+oldT)
				&&restoredProfiles.containsKey(expandedRadius+":0")&&restoredProfiles.containsKey(expandedRadius+":"+cancelStep),
				"save/load keeps the advanced partly paid outer cursor, material request, completion flag, and both wall profile sets");passed++;

			Builder.cancel(ref,now+800);
			check(!ref.data().hasPlan()&&ref.data().wanted.isEmpty()&&ref.data().wallBuilt
				&&Builder.columnIntact(level,bell,style,oldRadius,oldT)&&!Builder.columnIntact(level,bell,style,expandedRadius,cancelStep),
				"cancel clears the request but preserves the old wall and already paid blocks in the incomplete outer column");passed++;

			box.clearContent();for(int i=0;i<3;i++)box.setItem(i,new ItemStack(Items.COBBLESTONE,64));
			long restartAt=now+1000;
			check((boolean)start.invoke(null,new Ctx(level,worker,c.st(),c.mem(),ref,restartAt),ref,box,List.of(worker))
				&&ref.data().planVariant==expandedRadius&&ref.data().planStep==0&&ref.data().wallBuilt,
				"a cancelled growth plan restarts at the same outer radius without replacing the completed inner ring");passed++;
			List<Wall.Cell> resumedColumn=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),expandedRadius,cancelStep);
			List<Wall.Cell> resumedNextColumn=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),expandedRadius,cancelStep+1);
			int resumeCost=(int)(resumedColumn.stream().filter(Wall.Cell::paid).count()-3
				+resumedNextColumn.stream().filter(Wall.Cell::paid).count());
			int resumeStock=JobUtil.count(box,s->s.is(Items.COBBLESTONE));
			setFixtureStand(level,world,new BlockPos(cancelX,bell.getY(),cancelZ));
			cancelY=Wall.groundHeight(level,cancelX,cancelZ,style.wall());worker.teleportTo(cancelX+0.5,cancelY,cancelZ+0.5);
			Builder.tick(new Ctx(level,worker,c.st(),c.mem(),ref,restartAt+200),true);
			check(ref.data().planStep>cancelStep&&Builder.columnIntact(level,bell,style,expandedRadius,cancelStep)
				&&Builder.columnIntact(level,bell,style,oldRadius,oldT)&&JobUtil.count(box,s->s.is(Items.COBBLESTONE))==resumeStock-resumeCost,
				"resumed extension skips complete outer columns, finishes the partly paid column, and charges only new blocks");passed++;

			int legacyT=0;int[] legacyOffset=Wall.ringOffset(oldRadius,legacyT);var legacyIn=Wall.inward(oldRadius,legacyT);
			for(int k=0;k<Wall.THICK+6;k++)level.getChunkAt(new BlockPos(bell.getX()+legacyOffset[0]+legacyIn.getStepX()*k,bell.getY(),bell.getZ()+legacyOffset[1]+legacyIn.getStepZ()*k));
			List<Wall.Cell> legacyCells=Wall.column(level,bell,style.wall(),style.stairs(),style.fence(),oldRadius,legacyT);
			if(legacyCells.isEmpty())throw new IllegalStateException("WALL CHECK FAILED: legacy inner-column fixture unavailable");
			for(Wall.Cell cell:legacyCells){world.putIfAbsent(cell.pos(),level.getBlockState(cell.pos()));level.setBlock(cell.pos(),cell.state(),FLAGS);}
			for(Wall.Cell cell:legacyCells)if(!cell.state().isAir())level.setBlock(cell.pos(),Blocks.AIR.defaultBlockState(),FLAGS);
			long staleNow=now+40000;
			ref.data().planType=Blueprints.WALL;ref.data().planOrigin=Optional.of(bell);ref.data().planStep=8*expandedRadius+1;
			ref.data().planVariant=expandedRadius|(oldRadius<<8);ref.data().planProgress=staleNow-36001;ref.data().wallBuilt=true;ref.data().wanted.clear();
			var abort=Builder.class.getDeclaredMethod("abort",VillageData.class,VillageRef.class,long.class);abort.setAccessible(true);
			abort.invoke(null,ref.data(),ref,staleNow);
			check(ref.data().hasPlan()&&ref.data().planType.equals(Blueprints.WALL)&&ref.data().planOrigin.filter(bell::equals).isPresent()
				&&ref.data().planVariant==oldRadius&&ref.data().planStep==0&&ref.data().planProgress==staleNow&&ref.data().wallBuilt
				&&!Builder.columnIntact(level,bell,style,oldRadius,legacyT),
				"stale abort migrates an old packed cursor into a persistent paid repair for its partially removed inner ring");passed++;
			return passed;
		} finally {
			if(poiAdded&&exteriorPoi!=null)manager.remove(exteriorPoi);
			if(injectMeeting){manager.remove(bell);if(previousBellPoi!=null)manager.add(bell,previousBellPoi);}
			if(previousCheck==null)checked.remove(bell.asLong());else checked.put(bell.asLong(),previousCheck);
		}
	}

	private static int checkDiagonalHomeDiscovery(ServerLevel level,VillageRef ref) {
		var bell=ref.bell();
		var manager=level.getPoiManager();
		BoundingBox before=Development.bounds(level,ref);
		int beforeEdge=edge(before,bell);
		int diagonalOffset=Math.max(140,beforeEdge+1);
		if(diagonalOffset+14>Development.MAX_WALL_RADIUS)
			throw new IllegalStateException("WALL CHECK FAILED: fixture bounds leave no safe diagonal HOME-POI case");
		var meetings=manager.getInRange(h->h.is(PoiTypes.MEETING),bell,Development.MEETING_POI_DISCOVERY_RADIUS,
			PoiManager.Occupancy.ANY).map(p->p.getPos().immutable()).toList();
		BlockPos poi=null;
		for(BlockPos candidate:List.of(
			bell.offset(diagonalOffset,0,diagonalOffset),bell.offset(diagonalOffset,0,-diagonalOffset),
			bell.offset(-diagonalOffset,0,diagonalOffset),bell.offset(-diagonalOffset,0,-diagonalOffset))) {
			if(manager.getType(candidate).isPresent()||inside(before,candidate))continue;
			double nearestOther=meetings.stream().filter(point->!point.equals(bell)).mapToDouble(point->point.distSqr(candidate)).min().orElse(Double.MAX_VALUE);
			if(candidate.distSqr(bell)<nearestOther){poi=candidate;break;}
		}
		if(poi==null)throw new IllegalStateException("WALL CHECK FAILED: no diagonal HOME POI belongs unambiguously to this bell");
		var previousExtent=ref.bellEntity().getAttached(VillagerPlus.VILLAGE_EXTENT);
		boolean added=false;
		try {
			level.getChunkAt(poi);
			manager.add(poi,BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME));added=true;
			BoundingBox after=Development.bounds(level,ref);
			double distanceSquared=poi.distSqr(bell);
			check(distanceSquared>(long)Development.MAX_WALL_RADIUS*Development.MAX_WALL_RADIUS
				&&distanceSquared<=(long)Development.HOME_POI_DISCOVERY_RADIUS*Development.HOME_POI_DISCOVERY_RADIUS
				&&inside(after,poi)&&edge(after,bell)>=diagonalOffset&&edge(after,bell)>beforeEdge
				&&Development.neededWallRadius(level,ref)<=Development.MAX_WALL_RADIUS,
				"diagonal HOME POIs beyond the old circular wall radius are found inside the supported square footprint");
			return 1;
		} finally {
			if(added)manager.remove(poi);
			if(previousExtent==null)ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);
			else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT,previousExtent);
			ref.dirty();
		}
	}

	private static int checkNeighborHomeAttribution(ServerLevel level,VillageRef ref) {
		var bell=ref.bell();
		var manager=level.getPoiManager();
		BoundingBox before=Development.bounds(level,ref);
		var previousExtent=ref.bellEntity().getAttached(VillagerPlus.VILLAGE_EXTENT);
		var legacyMeetings=manager.getInRange(h->h.is(PoiTypes.MEETING),bell,Config.VILLAGE_RADIUS*3,
			PoiManager.Occupancy.ANY).map(p->p.getPos().immutable()).toList();
		var wideMeetings=manager.getInRange(h->h.is(PoiTypes.MEETING),bell,Development.MEETING_POI_DISCOVERY_RADIUS,
			PoiManager.Occupancy.ANY).map(p->p.getPos().immutable()).toList();
		int oldMeetingRadius=Config.VILLAGE_RADIUS*3;
		int homeOffset=Math.max(Math.max(180,edge(before,bell)+1),oldMeetingRadius/2+2);
		int neighborMeetingOffset=oldMeetingRadius+1;
		if(homeOffset>Development.HOME_POI_DISCOVERY_RADIUS||neighborMeetingOffset>Development.MEETING_POI_DISCOVERY_RADIUS
			||neighborMeetingOffset>=2*homeOffset)
			throw new IllegalStateException("WALL CHECK FAILED: no supported geometry for a neighboring-village meeting fixture");
		BlockPos home=null,neighborMeeting=null;
		for(BlockPos homeBase:List.of(bell.east(homeOffset),bell.west(homeOffset),bell.south(homeOffset),bell.north(homeOffset))) {
			BlockPos meetingBase=bell.relative(directionFrom(bell,homeBase),neighborMeetingOffset);
			for(int dy=0;dy<=8;dy++){
				BlockPos candidateHome=homeBase.above(dy),candidateMeeting=meetingBase.above(dy);
				if(manager.getType(candidateHome).isPresent()||manager.getType(candidateMeeting).isPresent()||inside(before,candidateHome)
					||candidateHome.distSqr(bell)>(long)Development.HOME_POI_DISCOVERY_RADIUS*Development.HOME_POI_DISCOVERY_RADIUS
					||candidateMeeting.distSqr(bell)>(long)Development.MEETING_POI_DISCOVERY_RADIUS*Development.MEETING_POI_DISCOVERY_RADIUS)continue;
				double bellDistance=candidateHome.distSqr(bell);
				boolean oldSearchWouldMisassign=legacyMeetings.stream().filter(point->!point.equals(bell))
					.noneMatch(point->point.distSqr(candidateHome)<=bellDistance);
				double neighborDistance=candidateMeeting.distSqr(candidateHome);
				boolean newSearchFindsNeighborFirst=wideMeetings.stream().filter(point->!point.equals(bell))
					.noneMatch(point->point.distSqr(candidateHome)<=neighborDistance);
				if(oldSearchWouldMisassign&&newSearchFindsNeighborFirst){home=candidateHome;neighborMeeting=candidateMeeting;break;}
			}
			if(home!=null)break;
		}
		if(home==null)throw new IllegalStateException("WALL CHECK FAILED: no fixture where the legacy meeting radius misassigns a neighbor HOME POI");
		boolean meetingAdded=false,homeAdded=false;
		try {
			level.getChunkAt(neighborMeeting);level.getChunkAt(home);
			manager.add(neighborMeeting,BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.MEETING));meetingAdded=true;
			manager.add(home,BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME));homeAdded=true;
			BoundingBox after=Development.bounds(level,ref);
			long oldMeetingRadiusSquared=(long)oldMeetingRadius*oldMeetingRadius;
			BlockPos selectedHome=home,selectedNeighborMeeting=neighborMeeting;
			List<BlockPos> refreshedMeetings=manager.getInRange(h->h.is(PoiTypes.MEETING),bell,Development.MEETING_POI_DISCOVERY_RADIUS,
				PoiManager.Occupancy.ANY).map(p->p.getPos().immutable()).toList();
			double foreignDistance=selectedNeighborMeeting.distSqr(selectedHome);
			boolean foreignMeetingIsNearest=refreshedMeetings.contains(selectedNeighborMeeting)
				&&refreshedMeetings.stream().filter(point->!point.equals(selectedNeighborMeeting))
					.noneMatch(point->point.distSqr(selectedHome)<=foreignDistance);
			check(home.distSqr(bell)<=(long)Development.HOME_POI_DISCOVERY_RADIUS*Development.HOME_POI_DISCOVERY_RADIUS
				&&neighborMeeting.distSqr(bell)>oldMeetingRadiusSquared
				&&neighborMeeting.distSqr(bell)<=(long)Development.MEETING_POI_DISCOVERY_RADIUS*Development.MEETING_POI_DISCOVERY_RADIUS
				&&foreignMeetingIsNearest&&foreignDistance<selectedHome.distSqr(bell)&&sameBounds(before,after),
				"the expanded meeting search finds the neighbor's closer meeting and excludes its HOME POI from this bell's bounds");
			return 1;
		} finally {
			if(homeAdded)manager.remove(home);
			if(meetingAdded)manager.remove(neighborMeeting);
			if(previousExtent==null)ref.bellEntity().removeAttached(VillagerPlus.VILLAGE_EXTENT);
			else ref.bellEntity().setAttached(VillagerPlus.VILLAGE_EXTENT,previousExtent);
			ref.dirty();
		}
	}

	private static net.minecraft.core.Direction directionFrom(BlockPos center,BlockPos point) {
		if(point.getX()>center.getX())return net.minecraft.core.Direction.EAST;
		if(point.getX()<center.getX())return net.minecraft.core.Direction.WEST;
		if(point.getZ()>center.getZ())return net.minecraft.core.Direction.SOUTH;
		return net.minecraft.core.Direction.NORTH;
	}

	private static int edge(BoundingBox bounds,BlockPos center) {
		return Math.max(Math.max(Math.abs(bounds.minX()-center.getX()),Math.abs(bounds.maxX()-center.getX())),
			Math.max(Math.abs(bounds.minZ()-center.getZ()),Math.abs(bounds.maxZ()-center.getZ())));
	}

	private static boolean inside(BoundingBox bounds,BlockPos point) {
		return point.getX()>=bounds.minX()&&point.getX()<=bounds.maxX()&&point.getZ()>=bounds.minZ()&&point.getZ()<=bounds.maxZ();
	}

	private static boolean sameBounds(BoundingBox a,BoundingBox b) {
		return a.minX()==b.minX()&&a.minY()==b.minY()&&a.minZ()==b.minZ()
			&&a.maxX()==b.maxX()&&a.maxY()==b.maxY()&&a.maxZ()==b.maxZ();
	}

	private static void setFixtureStand(ServerLevel level,Map<BlockPos,BlockState> world,BlockPos feet) {
		for(int dy=-1;dy<=1;dy++) {
			BlockPos pos=feet.offset(0,dy,0);
			level.getChunkAt(pos);
			if(level.getBlockEntity(pos)!=null)throw new IllegalStateException("WALL CHECK FAILED: fixture stand overlaps block entity at "+pos.toShortString());
			world.putIfAbsent(pos,level.getBlockState(pos));
			level.setBlock(pos,dy==-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState(),FLAGS);
		}
	}

	private static void check(boolean ok,String label){if(!ok)throw new IllegalStateException("WALL CHECK FAILED: "+label);System.out.println("WALL PASS "+label);}
	private static void check(boolean ok,String label,String detail){if(!ok)throw new IllegalStateException("WALL CHECK FAILED: "+label+" ["+detail+"]");System.out.println("WALL PASS "+label);}
}
