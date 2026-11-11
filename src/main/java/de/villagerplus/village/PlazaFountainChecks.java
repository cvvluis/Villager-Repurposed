package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Palette;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Isolated in-world checks for the paid civic fountain, plaza details, and real materials. */
public final class PlazaFountainChecks {
	private PlazaFountainChecks() { }

	private record Cell(BlockState state, CompoundTag blockEntity) { }

	public static int run(ServerLevel level, BlockPos at) {
		BlockPos center=at.east(20),stockPos=center.east(8);
		Map<BlockPos,Cell> before=new LinkedHashMap<>();
		int checks=checkCivicComposition();
		try {
			for(int dx=-4;dx<=4;dx++) for(int dz=-4;dz<=4;dz++) for(int dy=-1;dy<=5;dy++) {
				BlockPos pos=center.offset(dx,dy,dz);
				captureAndClear(level,before,pos);
			}
			for(int dy=0;dy<=1;dy++) captureAndClear(level,before,at.above(dy));
			for(int dy=-1;dy<=1;dy++) captureAndClear(level,before,stockPos.above(dy));

			level.setBlock(at,Blocks.BELL.defaultBlockState().setValue(BellBlock.FACING,Direction.NORTH),3);
			if(!(level.getBlockEntity(at) instanceof BellBlockEntity bell))
				throw new IllegalStateException("Plaza fountain fixture bell was not created");
			VillageData data=new VillageData();
			bell.setAttached(VillagerPlus.VILLAGE,data);
			VillageRef ref=new VillageRef(at,data,bell);

			level.setBlock(stockPos,Blocks.CHEST.defaultBlockState(),3);
			Container stock=HopperBlockEntity.getContainerAt(level,stockPos);
			if(stock==null) throw new IllegalStateException("Plaza fountain fixture stock chest was not created");
			stock.clearContent();
			stock.setItem(0,new ItemStack(Items.WATER_BUCKET));
			data.chest=Optional.of(stockPos);
			check(!Plaza.hasPublicMaterials(level,ref,Blocks.STONE_BRICKS)&&count(stock,Items.WATER_BUCKET)==1,
				"a missing plaza block item is detected before work and leaves the public water stock untouched"); checks++;
			stock.setItem(1,new ItemStack(Blocks.STONE_BRICKS.asItem())); stock.setChanged();
			check(Plaza.hasPublicMaterials(level,ref,Blocks.STONE_BRICKS)
				&&count(stock,Blocks.STONE_BRICKS.asItem())==1&&count(stock,Items.WATER_BUCKET)==1,
				"palette paving can proceed only after its matching real block item is in public stock"); checks++;
			stock.setItem(1,ItemStack.EMPTY); stock.setChanged();

			Palette palette=new Palette(Blocks.STONE_BRICKS,Blocks.STONE_BRICKS,Blocks.STONE_BRICKS,
				Blocks.STONE_BRICKS,Blocks.STONE_BRICKS,Blocks.STONE_BRICK_STAIRS,Blocks.STONE_BRICK_SLAB,
				Blocks.GLASS_PANE,Blocks.STONE_BRICKS,Blocks.OAK_DOOR,Blocks.SPRUCE_FENCE);
			for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) {
				level.setBlock(center.offset(dx,-1,dz),Blocks.STONE.defaultBlockState(),3);
				level.setBlock(center.offset(dx,0,dz),Blocks.STONE_BRICKS.defaultBlockState(),3);
			}
			level.setBlock(center.above(),Blocks.STONE_BRICKS.defaultBlockState(),3);
			level.setBlock(center.above(2),Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),3);
			level.setBlock(center.above(3),Blocks.LANTERN.defaultBlockState(),3);

			check(Plaza.ensureFountain(level,ref,center,palette),
				"a furnished civic center installs its paid fountain from a real public water bucket"); checks++;
			check(level.getBlockState(center).is(Blocks.WATER)
				&& level.getBlockState(center).getFluidState().isSource(),
				"the fountain leaves a persistent source in the center of its basin"); checks++;
			boolean sealedRim=true;
			for(int dx=-2;dx<=2;dx++) for(int dz=-2;dz<=2;dz++) if(Plaza.fountainRingOffset(dx,dz))
				sealedRim&=level.getBlockState(center.offset(dx,0,dz)).is(Blocks.STONE_BRICKS);
			check(sealedRim,"the symmetric five-by-five rim contains the three-by-three water basin"); checks++;
			check(level.getBlockState(center.above()).isAir()&&level.getBlockState(center.above(2)).isAir()
				&&level.getBlockState(center.above(3)).isAir(),
				"the replaced dry shrine marker is removed without leaving blocks above the water"); checks++;
			check(count(stock,Items.WATER_BUCKET)==0&&count(stock,Items.BUCKET)==1,
				"the water bucket is consumed and one empty bucket returns to its exact public source slot"); checks++;
			check(Plaza.ensureFountain(level,ref,center,palette)&&count(stock,Items.BUCKET)==1,
				"a repeated plaza tick recognizes the completed fountain without charging twice"); checks++;

			for(int dx=-1;dx<=1;dx++) for(int dz=-1;dz<=1;dz++)
				level.setBlock(center.offset(dx,0,dz),Blocks.STONE_BRICKS.defaultBlockState(),3);
			level.setBlock(center.above(),Blocks.STONE_BRICKS.defaultBlockState(),3);
			level.setBlock(center.above(2),Blocks.CHISELED_STONE_BRICKS.defaultBlockState(),3);
			level.setBlock(center.above(3),Blocks.LANTERN.defaultBlockState(),3);
			check(!Plaza.ensureFountain(level,ref,center,palette)
				&& level.getBlockState(center).is(Blocks.STONE_BRICKS)
				&& level.getBlockState(center.above()).is(Blocks.STONE_BRICKS)
				&& level.getBlockState(center.above(2)).is(Blocks.CHISELED_STONE_BRICKS)
				&& count(stock,Items.BUCKET)==1,
				"without a real water bucket, plaza retry preserves the dry marker and public inventory"); checks++;
			stock.setItem(1,new ItemStack(Items.WATER_BUCKET)); stock.setChanged();
			level.setBlock(center.east().above(),Blocks.GLASS.defaultBlockState(),3);
			check(!Plaza.ensureFountain(level,ref,center,palette)
				&& level.getBlockState(center).is(Blocks.STONE_BRICKS)
				&& level.getBlockState(center.east().above()).is(Blocks.GLASS)
				&& count(stock,Items.WATER_BUCKET)==1&&count(stock,Items.BUCKET)==1,
				"the fountain leaves unrelated overhead blocks and both buckets untouched"); checks++;

			BlockPos support=center.offset(3,-1,0),candidate=support.above();
			captureAndClear(level,before,support); captureAndClear(level,before,candidate);
			level.setBlock(support,Blocks.STONE_BRICKS.defaultBlockState(),3);
			check(Plaza.decorationCellFree(level,candidate,Set.of()),
				"an empty supported plaza cell remains eligible for decoration"); checks++;
			check(!Plaza.decorationCellFree(level,candidate,Set.of(support.asLong())),
				"saved road ground is protected from plaza furniture"); checks++;
			level.setBlock(support,Blocks.CHEST.defaultBlockState(),3);
			check(!Plaza.decorationCellFree(level,candidate,Set.of()),
				"a container support is protected even when the cell above is empty"); checks++;

			stock.setItem(2,new ItemStack(Blocks.CAULDRON.asItem(),4)); stock.setChanged();
			for(int dx:new int[]{-3,3}) for(int dz:new int[]{-3,3})
				level.setBlock(center.offset(dx,-1,dz),Blocks.STONE_BRICKS.defaultBlockState(),3);
			Plaza.CivicBatch rainBasins=Plaza.decorateCivicBatch(level,ref,center,palette,Set.of(),Plaza.allCivicDetailCount());
			boolean allRainBasins=true;
			for(int dx:new int[]{-3,3}) for(int dz:new int[]{-3,3})
				allRainBasins&=level.getBlockState(center.offset(dx,0,dz)).is(Blocks.CAULDRON);
			check(rainBasins.cursor()==Plaza.civicWorkItemCount()&&!rainBasins.pending()&&allRainBasins
				&&count(stock,Blocks.CAULDRON.asItem())==0,
				"the four rain basins are installed around the pavilion from four real public cauldrons"); checks++;
			return checks;
		} finally {
			for(Map.Entry<BlockPos,Cell> entry:before.entrySet()) restore(level,entry.getKey(),entry.getValue());
		}
	}

	private static int checkCivicComposition() {
		int checks=0,benches=0,planters=0,flowers=0,lamps=0,rainBasins=0;
		int outerBenches=0,outerPlanters=0,outerFlowers=0,outerLamps=0;
		Set<String> occupied=new java.util.HashSet<>();
		boolean symmetric=true;
		for(int dx=-13;dx<=13;dx++) for(int dz=-13;dz<=13;dz++) {
			boolean bench=Plaza.civicBenchOffset(dx,dz),planter=Plaza.civicPlanterOffset(dx,dz);
			boolean flower=Plaza.civicFlowerOffset(dx,dz),lamp=Plaza.civicLampOffset(dx,dz);
			boolean outerBench=Plaza.outerCivicBenchOffset(dx,dz),outerPlanter=Plaza.outerCivicPlanterOffset(dx,dz);
			boolean outerFlower=Plaza.outerCivicFlowerOffset(dx,dz),outerLamp=Plaza.outerCivicLampOffset(dx,dz);
			boolean basin=Plaza.civicRainBasinOffset(dx,dz);
			boolean anyBench=bench||outerBench,anyLamp=lamp||outerLamp;
			int kinds=(bench?1:0)+(planter?1:0)+(flower?1:0)+(lamp?1:0)+(outerBench?1:0)
				+(outerPlanter?1:0)+(outerFlower?1:0)+(outerLamp?1:0)+(basin?1:0);
			if(kinds==0) continue;
			check(kinds==1&&occupied.add(dx+":"+dz),"each civic detail has one unique coordinate"); checks++;
			check(Plaza.civicDetailOffsetSafe(dx,dz,anyLamp),
				"civic details stay outside the pavilion, roads, board, and container access"); checks++;
			check(Math.abs(dx)>2&&Math.abs(dz)>2&&!Plaza.roadArmOffset(dx,dz),
				"the four five-wide walking approaches remain open"); checks++;
			if(basin) {
				check(Plaza.rainBasinOffset(dx,dz)&&Math.abs(dx)==3&&Math.abs(dz)==3,
					"rain basins frame the central pavilion without closing its approaches"); checks++;
			}
			if(anyBench) {
				check(!Plaza.focalSquareBorderOffset(dx,dz),"seating stays outside the centerpiece's square paving frame"); checks++;
			}
			benches+=bench?1:0; planters+=planter?1:0; flowers+=flower?1:0; lamps+=lamp?1:0;
			outerBenches+=outerBench?1:0; outerPlanters+=outerPlanter?1:0;
			outerFlowers+=outerFlower?1:0; outerLamps+=outerLamp?1:0;
			rainBasins+=basin?1:0;
			symmetric&=bench==Plaza.civicBenchOffset(-dz,dx)
				&&planter==Plaza.civicPlanterOffset(-dz,dx)
				&&flower==Plaza.civicFlowerOffset(-dz,dx)
				&&lamp==Plaza.civicLampOffset(-dz,dx)
				&&outerBench==Plaza.outerCivicBenchOffset(-dz,dx)
				&&outerPlanter==Plaza.outerCivicPlanterOffset(-dz,dx)
				&&outerFlower==Plaza.outerCivicFlowerOffset(-dz,dx)
				&&outerLamp==Plaza.outerCivicLampOffset(-dz,dx)
				&&basin==Plaza.civicRainBasinOffset(-dz,dx);
		}
		check(benches==8&&planters==4&&flowers==16&&lamps==4&&rainBasins==4&&Plaza.civicDetailCount()==32,
			"the inner pavilion contains eight seats, four shrubs, sixteen flowers, four lights, and four rain basins"); checks++;
		check(outerBenches==8&&outerPlanters==8&&outerFlowers==16&&outerLamps==4&&Plaza.outerCivicDetailCount()==36,
			"the outer promenade adds eight seats, eight shrubs, sixteen flowers, and four lights"); checks++;
		check(Plaza.allCivicDetailCount()==68&&Plaza.civicWorkItemCount()==72,
			"four rain basins append to the 68 inner-court and promenade furniture sites"); checks++;
		check(symmetric,"the civic composition is rotationally balanced around its fountain"); checks++;
		check(!Plaza.civicDetailOffsetSafe(0,4,false)
			&&!Plaza.civicDetailOffsetSafe(-2,4,false)
			&&!Plaza.civicDetailOffsetSafe(-1,-5,false)
			&&!Plaza.civicDetailOffsetSafe(0,-4,false)
			&&!Plaza.civicDetailOffsetSafe(0,0,false),
			"furniture stays out of the fountain pavilion and the public chest and board approach lanes"); checks++;
		int focalBorder=0;
		for(int dx=-6;dx<=6;dx++) for(int dz=-6;dz<=6;dz++) if(Plaza.focalSquareBorderOffset(dx,dz)) {
			focalBorder++;
			check(Plaza.basePavingOffset(dx,dz),"the central square frame uses the palette paving accent"); checks++;
		}
		check(focalBorder==48,"a complete 13-by-13 paving frame draws attention to the fountain pavilion"); checks++;
		boolean connectedApproaches=true;
		for(int n=0;n<=13;n++) for(int side=-2;side<=2;side++)
			connectedApproaches&=Plaza.basePavingOffset(n,side)&&Plaza.basePavingOffset(side,n);
		for(int n=14;n<=18;n++) for(int side=-2;side<=2;side++)
			connectedApproaches&=Plaza.roadArmOffset(n,side)&&Plaza.roadArmOffset(side,n);
		check(connectedApproaches,"five-block cross approaches stay paved through the plaza and into each road arm"); checks++;
		check(Plaza.civicDetailBudget()<=4&&Plaza.surfaceWorkBudget()<=8,
			"surface and furniture work stay within fixed per-tick budgets"); checks++;
		check(Plaza.workBatchIntervalTicks()==1,
			"the bounded civic build advances each server tick instead of sleeping between batches"); checks++;
		check(Plaza.materialCost(Blocks.STONE_BRICK_STAIRS,Blocks.STONE_BRICK_STAIRS).orElseThrow()
			.equals(Map.of(Blocks.STONE_BRICK_STAIRS.asItem(),2)),
			"each palette bench charges both of its real stair-block items"); checks++;
		check(Plaza.materialCost(Blocks.CAULDRON).orElseThrow().equals(Map.of(Blocks.CAULDRON.asItem(),1)),
			"each decorative rain basin charges its actual cauldron item"); checks++;
		return checks;
	}

	private static int count(Container container,net.minecraft.world.item.Item item) {
		int count=0;
		for(int slot=0;slot<container.getContainerSize();slot++) {
			ItemStack stack=container.getItem(slot);
			if(!stack.isEmpty()&&stack.is(item)) count+=stack.getCount();
		}
		return count;
	}

	private static void captureAndClear(ServerLevel level,Map<BlockPos,Cell> before,BlockPos pos) {
		level.getChunkAt(pos);
		BlockEntity entity=level.getBlockEntity(pos);
		before.putIfAbsent(pos.immutable(),new Cell(level.getBlockState(pos),
			entity==null?null:entity.saveWithFullMetadata(level.registryAccess())));
		level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);
	}

	private static void restore(ServerLevel level,BlockPos pos,Cell cell) {
		level.removeBlockEntity(pos);
		level.setBlock(pos,cell.state(),3);
		if(cell.blockEntity()!=null) {
			BlockEntity restored=BlockEntity.loadStatic(pos,cell.state(),cell.blockEntity().copy(),level.registryAccess());
			if(restored!=null) level.setBlockEntity(restored);
		}
	}

	private static void check(boolean value,String message) {
		if(!value) throw new IllegalStateException("Plaza fountain checks: "+message);
	}
}
