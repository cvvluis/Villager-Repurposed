package de.villagerplus.village;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Focused checks for plaza furnishing geometry, market-site spacing, and saved civic-center position. */
public final class PlazaChecks {
	private PlazaChecks() { }
	private static int checks;

	public static int run() {
		checks=0;
		BlockPos center = new BlockPos(120, 64, -80);
		java.util.Set<String> uniqueDecor=new java.util.HashSet<>();
		int seats=0,planters=0,flowers=0,basins=0,lamps=0;
		for(int dx=-12;dx<=12;dx++) for(int dz=-12;dz<=12;dz++) {
			boolean seat=Plaza.benchOffset(dx,dz),planter=Plaza.planterOffset(dx,dz),flower=Plaza.flowerOffset(dx,dz),
				basin=Plaza.rainBasinOffset(dx,dz),lamp=Plaza.lampOffset(dx,dz);
			if(!seat&&!planter&&!flower&&!basin&&!lamp) continue;
			require(lamp ? Plaza.lampOffsetSafe(dx,dz) : Plaza.decorationOffsetSafe(dx,dz),
				"every seat, planter, flower, basin, and lamp stays in its safe outer-ring cell");
			require(uniqueDecor.add(dx+":"+dz), "decoration cells do not overlap each other");
			require(Plaza.inside(center, center.offset(dx,0,dz), 0), "added decoration stays inside the existing plaza footprint");
			require((seat?1:0)+(planter?1:0)+(flower?1:0)+(basin?1:0)+(lamp?1:0)==1, "each planned decoration cell has one purpose");
			if(seat) { seats++; require(Plaza.benchOffset(-dx,dz)&&Plaza.benchOffset(dx,-dz), "seating is balanced across both plaza axes"); }
			if(planter) { planters++; require(Plaza.planterOffset(-dx,dz)&&Plaza.planterOffset(dx,-dz), "greenery is balanced across both plaza axes"); }
			if(flower) { flowers++; require(Plaza.flowerOffset(-dx,dz)&&Plaza.flowerOffset(dx,-dz), "flower beds are balanced across both plaza axes"); }
			if(basin) { basins++; require(Plaza.rainBasinOffset(-dx,dz)&&Plaza.rainBasinOffset(dx,-dz)&&Plaza.rainBasinOffset(-dx,-dz), "rain basins are balanced across all four plaza quadrants"); }
			if(lamp) { lamps++; require(Plaza.lampOffset(-dx,dz)&&Plaza.lampOffset(dx,-dz), "lamp posts are balanced across both plaza axes"); }
		}
		require(seats==40&&planters==32&&flowers==52&&basins==4&&lamps==16,
			"the expanded square's legacy map exposes forty seats, thirty-two planters, fifty-two flowers, four basins, and four light rings");
		int[] courtSeats=new int[4],courtPlanters=new int[4],courtFlowers=new int[4];
		int innerSeats=0,innerPlanters=0,innerFlowers=0,innerLamps=0;
		int outerSeats=0,outerPlanters=0,outerFlowers=0,outerLamps=0;
		java.util.Set<String> civicCells=new java.util.HashSet<>();
		boolean civicRotational=true;
		for(int dx=-12;dx<=12;dx++) for(int dz=-12;dz<=12;dz++) {
			int court=Plaza.gardenCourtIndex(dx,dz);
			boolean seat=Plaza.civicBenchOffset(dx,dz),planter=Plaza.civicPlanterOffset(dx,dz);
			boolean flower=Plaza.civicFlowerOffset(dx,dz),lamp=Plaza.civicLampOffset(dx,dz);
			boolean outerSeat=Plaza.outerCivicBenchOffset(dx,dz),outerPlanter=Plaza.outerCivicPlanterOffset(dx,dz);
			boolean outerFlower=Plaza.outerCivicFlowerOffset(dx,dz),outerLamp=Plaza.outerCivicLampOffset(dx,dz);
			int purposes=(seat?1:0)+(planter?1:0)+(flower?1:0)+(lamp?1:0)
				+(outerSeat?1:0)+(outerPlanter?1:0)+(outerFlower?1:0)+(outerLamp?1:0);
			if(purposes>0) {
				require(purposes==1&&civicCells.add(dx+":"+dz), "each planned civic furnishing has one unique tile");
				require(Plaza.inside(center,center.offset(dx,0,dz),0), "all civic furnishings fit inside the paved footprint");
				require(Plaza.civicDetailOffsetSafe(dx,dz,lamp||outerLamp),
					"seats, shrubs, flowers, and lights stay in their safe ring outside approaches");
			}
			if(seat) { innerSeats++; if(court>=0) courtSeats[court]++; }
			if(planter) { innerPlanters++; if(court>=0) courtPlanters[court]++; }
			if(flower) { innerFlowers++; if(court>=0) courtFlowers[court]++; }
			if(lamp) { innerLamps++; require(Plaza.lampOffsetSafe(dx,dz), "inner civic lights stay on the safe edge ring"); }
			if(outerSeat) outerSeats++;
			if(outerPlanter) outerPlanters++;
			if(outerFlower) outerFlowers++;
			if(outerLamp) { outerLamps++; require(Plaza.lampOffsetSafe(dx,dz), "promenade lights stay on the safe edge ring"); }
			int rx=-dz,rz=dx;
			civicRotational&=Plaza.civicBenchOffset(dx,dz)==Plaza.civicBenchOffset(rx,rz)
				&&Plaza.civicPlanterOffset(dx,dz)==Plaza.civicPlanterOffset(rx,rz)
				&&Plaza.civicFlowerOffset(dx,dz)==Plaza.civicFlowerOffset(rx,rz)
				&&Plaza.civicLampOffset(dx,dz)==Plaza.civicLampOffset(rx,rz)
				&&Plaza.outerCivicBenchOffset(dx,dz)==Plaza.outerCivicBenchOffset(rx,rz)
				&&Plaza.outerCivicPlanterOffset(dx,dz)==Plaza.outerCivicPlanterOffset(rx,rz)
				&&Plaza.outerCivicFlowerOffset(dx,dz)==Plaza.outerCivicFlowerOffset(rx,rz)
				&&Plaza.outerCivicLampOffset(dx,dz)==Plaza.outerCivicLampOffset(rx,rz)
				&&((Plaza.gardenCourtIndex(dx,dz)>=0)==(Plaza.gardenCourtIndex(rx,rz)>=0));
		}
		require(innerSeats==8&&innerPlanters==4&&innerFlowers==16&&innerLamps==4
			&&outerSeats==8&&outerPlanters==8&&outerFlowers==16&&outerLamps==4,
			"the civic core has a defined inner garden ring and the promenade receives a balanced second furnishing ring");
		require(Plaza.civicDetailCount()==32&&Plaza.outerCivicDetailCount()==36
			&&Plaza.allCivicDetailCount()==68&&Plaza.civicWorkItemCount()==72,
			"the furnishing cursor spans every pavilion, garden, promenade, and rain-basin item exactly once");
		for(int court=0;court<4;court++) require(courtSeats[court]==2&&courtPlanters[court]==1&&courtFlowers[court]==4,
			"each quadrant forms one matching green court with a leafy planter, four flowers, and two inward-facing seats");
		require(civicRotational, "the four garden courts and outer promenade remain identical after each quarter-turn");
		require(Plaza.gardenCourtIndex(0,6)==-1&&Plaza.gardenCourtIndex(6,0)==-1
			&&Plaza.gardenCourtIndex(6,6)>=0&&Plaza.gardenCourtIndex(-6,6)>=0,
			"four garden courts stay separated by the central pavilion's cardinal approaches");
		require(Plaza.civicDetailOffsetSafe(-7,-7,false)&&Plaza.civicDetailOffsetSafe(7,-7,false)
			&&Plaza.civicDetailOffsetSafe(-7,7,false)&&Plaza.civicDetailOffsetSafe(7,7,false)
			&&Plaza.civicDetailOffsetSafe(-3,-3,false)&&Plaza.civicDetailOffsetSafe(3,-3,false)
			&&Plaza.civicDetailOffsetSafe(-3,3,false)&&Plaza.civicDetailOffsetSafe(3,3,false)
			&&!Plaza.civicDetailOffsetSafe(0,7,false)&&!Plaza.civicDetailOffsetSafe(7,0,false)
			&&!Plaza.civicDetailOffsetSafe(9,9,false),
			"four court planters and four fountain-side basins stay placeable while cardinal approaches and active lamp cells remain clear");
		require(Plaza.benchFacing(1)==net.minecraft.core.Direction.EAST
			&&Plaza.benchFacing(2)==net.minecraft.core.Direction.SOUTH
			&&Plaza.benchFacing(-1)==net.minecraft.core.Direction.WEST
			&&Plaza.benchFacing(-2)==net.minecraft.core.Direction.NORTH,
			"bench seats face inward along each quarter-turn");
		boolean rotationallySymmetric=true;
		for(int dx=-12;dx<=12;dx++) for(int dz=-12;dz<=12;dz++) {
			int rotatedX=-dz,rotatedZ=dx;
			rotationallySymmetric&=Plaza.benchOffset(dx,dz)==Plaza.benchOffset(rotatedX,rotatedZ)
				&&Plaza.planterOffset(dx,dz)==Plaza.planterOffset(rotatedX,rotatedZ)
				&&Plaza.flowerOffset(dx,dz)==Plaza.flowerOffset(rotatedX,rotatedZ)
				&&Plaza.rainBasinOffset(dx,dz)==Plaza.rainBasinOffset(rotatedX,rotatedZ)
				&&Plaza.lampOffset(dx,dz)==Plaza.lampOffset(rotatedX,rotatedZ);
		}
		require(rotationallySymmetric, "all plaza decorations keep their positions through each quarter-turn");
		require(!Plaza.decorationOffsetSafe(0,4)&&!Plaza.decorationOffsetSafe(1,-4)&&!Plaza.decorationOffsetSafe(-2,0),
			"the central and five-wide plaza approaches stay free");
		require(!Plaza.decorationOffsetSafe(0,0)&&!Plaza.decorationOffsetSafe(-2,2), "the shrine and its supports remain reserved");
		require(!Plaza.decorationOffsetSafe(-2,4)&&!Plaza.decorationOffsetSafe(-2,-5)
			&&!Plaza.decorationOffsetSafe(-1,-5)&&!Plaza.decorationOffsetSafe(0,-4), "the central lanes and Dorftafel/front access remain reserved");
		require(Plaza.benchOffset(-3,-4)&&Plaza.planterOffset(-3,-5),
			"side furnishings stay available outside the Dorftafel's actual footprint and five-wide access lane");
		require(!Plaza.decorationOffsetSafe(5,5)&&!Plaza.decorationOffsetSafe(7,7)&&!Plaza.decorationOffsetSafe(9,9)&&!Plaza.decorationOffsetSafe(11,11)
			&&!Plaza.decorationOffsetSafe(13,1)&&!Plaza.decorationOffsetSafe(12,9),
			"the four lamp rings and the expanded plaza edge stay clear");
		int surfaceCells=0,approachCells=0,inlayCells=0,middleFrameCells=0;
		boolean surfaceRotational=true;
		for(int dx=-13;dx<=13;dx++) for(int dz=-13;dz<=13;dz++) {
			int rotatedX=-dz,rotatedZ=dx;
			surfaceRotational&=Plaza.surfaceUpgradeOffsetSafe(dx,dz)==Plaza.surfaceUpgradeOffsetSafe(rotatedX,rotatedZ)
				&&Plaza.basePavingOffset(dx,dz)==Plaza.basePavingOffset(rotatedX,rotatedZ)
				&&Plaza.middleSquareBorderOffset(dx,dz)==Plaza.middleSquareBorderOffset(rotatedX,rotatedZ)
				&&Plaza.plazaInlayOffset(dx,dz)==Plaza.plazaInlayOffset(rotatedX,rotatedZ);
			if(Plaza.middleSquareBorderOffset(dx,dz)) {
				middleFrameCells++;
				require(Plaza.basePavingOffset(dx,dz)&&Plaza.surfaceUpgradeOffsetSafe(dx,dz)
					&&Plaza.inside(center,center.offset(dx,0,dz),0),
					"every cell of the third square frame is palette-paved inside the saved plaza footprint");
			}
			if(!Plaza.surfaceUpgradeOffsetSafe(dx,dz)) continue;
			surfaceCells++;
			if(Plaza.plazaInlayOffset(dx,dz)) {
				inlayCells++;
				require(Plaza.basePavingOffset(dx,dz), "every diamond and diagonal inlay uses the plaza accent block");
				require(Math.abs(dx)>2&&Math.abs(dz)>2, "the decorative pattern stays outside the five-wide approach lanes");
			}
			if(Math.abs(dx)<=2||Math.abs(dz)<=2) {
				approachCells++;
				require(Plaza.basePavingOffset(dx,dz), "all four five-block approaches retain continuous base paving");
			}
		}
		require(surfaceCells==689&&approachCells==245&&inlayCells==104&&middleFrameCells==64,
			"the 27x27 surface has a fountain medallion, double diamond, three nested square frames, and four continuous approaches"
				+" (actual surface="+surfaceCells+", approach="+approachCells+", inlay="+inlayCells+", middleFrame="+middleFrameCells+")");
		require(surfaceRotational, "the octagonal surface, centered medallions, and five-wide approach strips survive quarter-turns");
		require(Plaza.middleSquareBorderOffset(8,0)&&Plaza.middleSquareBorderOffset(-8,5)
			&&Plaza.middleSquareBorderOffset(0,-8)&&!Plaza.middleSquareBorderOffset(9,0)
			&&Plaza.basePavingOffset(8,0)&&!Plaza.decorationOffsetSafe(8,0),
			"the 17x17 frame marks the civic transition while leaving the five-wide road crossing unobstructed");
		Block middleBase=Plaza.surfaceMaterialForOffset(8,0,Blocks.SANDSTONE,Blocks.SMOOTH_SANDSTONE);
		Block ordinaryFloor=Plaza.surfaceMaterialForOffset(7,4,Blocks.SANDSTONE,Blocks.SMOOTH_SANDSTONE);
		require(middleBase==Blocks.SANDSTONE&&ordinaryFloor==Blocks.SMOOTH_SANDSTONE
			&&Plaza.materialCost(middleBase).orElseThrow().equals(Map.of(Items.SANDSTONE,1))
			&&Plaza.surfaceMaterialForOffset(8,0,null,Blocks.SMOOTH_SANDSTONE)==null,
			"the new frame is paid from one real palette-base item per cell, ordinary floor cells keep their palette floor, and missing materials fail closed");
		require(Plaza.plazaInlayOffset(3,4)&&Plaza.plazaInlayOffset(-3,4)
			&&Plaza.plazaInlayOffset(4,3)&&Plaza.plazaInlayOffset(4,-3)
			&&Plaza.plazaInlayOffset(3,9)&&Plaza.plazaInlayOffset(-3,9)
			&&Plaza.plazaInlayOffset(3,-9)&&Plaza.plazaInlayOffset(-3,-9)
			&&Plaza.plazaInlayOffset(7,13)&&Plaza.plazaInlayOffset(-7,13)
			&&Plaza.plazaInlayOffset(7,-13)&&Plaza.plazaInlayOffset(-7,-13),
			"the inner and outer paving medallions are symmetric in all four quadrants");
		require(Plaza.inside(center,center.east(13),0)&&!Plaza.inside(center,center.east(14),0)
			&&Plaza.inside(center,center.offset(13,0,9),0)&&!Plaza.inside(center,center.offset(13,0,10),0),
			"the expanded square has a clear, scaled octagonal edge");
		require(Plaza.outerSquareBorderOffset(10,0)&&Plaza.outerSquareBorderOffset(-10,7)
			&&!Plaza.outerSquareBorderOffset(11,0), "the outer frame stays complete, symmetric, and inside the paved footprint");
		Map<net.minecraft.world.item.Item,Integer> planterCost=Plaza.materialCost(Blocks.COBBLESTONE,Blocks.COBBLESTONE,Blocks.OAK_LEAVES).orElseThrow();
		require(planterCost.equals(Map.of(Items.COBBLESTONE,2,Items.OAK_LEAVES,1)), "planter groups charge every palette block exactly once");
		Map<net.minecraft.world.item.Item,Integer> lampCost=Plaza.materialCost(Blocks.STRIPPED_OAK_LOG,Blocks.STRIPPED_OAK_LOG,Blocks.LANTERN).orElseThrow();
		require(lampCost.equals(Map.of(Items.STRIPPED_OAK_LOG,2,Items.LANTERN,1)), "a lamp post charges both supports and its lantern together");
		require(Plaza.materialCost(Blocks.CAULDRON).orElseThrow().equals(Map.of(Items.CAULDRON,1)), "each rain basin requires one real cauldron item from public storage");
		require(Plaza.materialCost(Blocks.OAK_STAIRS).orElseThrow().equals(Map.of(Blocks.OAK_STAIRS.asItem(),1)), "each added bench charges its stair block");
		require(Plaza.materialCost(Blocks.POPPY).orElseThrow().equals(Map.of(Blocks.POPPY.asItem(),1)), "each flower accent charges its matching flower item");
		require(Plaza.materialCost(Blocks.AIR).isEmpty(), "non-item blocks can never pass as free decoration material");
		int fountainRim=0;
		boolean fountainSymmetric=true;
		for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
			boolean rim=Plaza.fountainRingOffset(dx,dz);
			if(rim) {
				fountainRim++;
				require(Plaza.surfaceUpgradeOffsetSafe(dx,dz), "the fountain stays on the saved civic-center footprint");
			}
			fountainSymmetric&=rim==Plaza.fountainRingOffset(-dz,dx);
		}
		require(fountainRim==16 && !Plaza.fountainRingOffset(0,0)
			&& !Plaza.fountainRingOffset(1,1) && Plaza.fountainRingOffset(2,0) && fountainSymmetric,
			"the centerpiece leaves a 3x3 water basin inside a centered, symmetric 5x5 rim");
		require(Plaza.fountainCost(Blocks.STONE_BRICKS).equals(Map.of(Items.WATER_BUCKET,1))
			&& Plaza.fountainBucketReturn()==Items.BUCKET,
			"the fountain uses one real public water bucket and returns the empty bucket to its source slot");

		for(net.minecraft.core.Direction face:net.minecraft.core.Direction.Plane.HORIZONTAL) {
			BlockPos originalStand=center.relative(face,17),movedStand=Plaza.clearApproachRoutes(center,originalStand,4);
			require(Plaza.overlapsApproach(center,originalStand,4), "a stall at radius 17 overlaps its wider plaza approach");
			require(movedStand.equals(center.relative(face,23)), "stalls move outward until every cardinal approach clears");
			require(!Plaza.overlapsApproach(center,movedStand,4), "the relocated stall leaves the full five-block path open");
		}
		require(Plaza.clearApproachRoutes(center, center.east(24), 4).equals(center.east(24)), "a site already outside the approach remains in place");
		require(Plaza.inside(center, center.east(22), 9) && !Plaza.inside(center, center.east(23), 9),
			"the plaza's persisted reserve ends after its five-block expansion buffer plus building footprint");

		MarketData saved = new MarketData(Optional.of(center), List.of(), 1234L, 0L);
		var encoded = MarketData.CODEC.encodeStart(com.mojang.serialization.JsonOps.INSTANCE, saved).getOrThrow();
		var decoded = MarketData.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, encoded).getOrThrow();
		require(decoded.equals(saved) && Plaza.inside(decoded.plaza().orElseThrow(), center.east(16), 9),
			"the civic center and its future-building reserve survive the saved-data codec round-trip");
		return checks;
	}

	private static void require(boolean condition, String message) {
		checks++;
		if (!condition) throw new IllegalStateException("Plaza checks: " + message);
	}
}
