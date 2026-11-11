package de.villagerplus.village;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** Focused checks for the paid three-lane route geometry and its existing paving palette. */
public final class RoadWidthChecks {
 private RoadWidthChecks() { }

 public static int run() {
  int checks=0;
  require(Roads.ROAD_HALF_WIDTH==1,"route width stays three lanes");checks++;
  checkThreeLanes(new BlockPos(0,64,0),new BlockPos(16,64,0));checks++;
  checkThreeLanes(new BlockPos(0,64,0),new BlockPos(0,64,16));checks++;
  require(Roads.paving(0,false)==Blocks.GRAVEL&&Roads.paving(-1,false)==Blocks.COBBLESTONE
   &&Roads.paving(1,false)==Blocks.COBBLESTONE,"temperate roads use a gravel crown with cobblestone edges");checks++;
  require(Roads.paving(0,true)==Blocks.SMOOTH_SANDSTONE&&Roads.paving(-1,true)==Blocks.SANDSTONE
   &&Roads.paving(1,true)==Blocks.SANDSTONE,"sand-biome roads use a smooth sandstone crown with sandstone edges");checks++;
  var grass=Blocks.GRASS_BLOCK.defaultBlockState();var sand=Blocks.SAND.defaultBlockState();
  require(Roads.sandPaletteForBiome("desert",grass)&&Roads.sandPaletteForBiome("badlands",grass)
   &&Roads.sandPaletteForBiome("beach",grass),"desert, badlands and beach biomes select the sandy palette");checks++;
  require(!Roads.sandPaletteForBiome("snowy_plains",grass)
   &&Roads.sandPaletteForBiome("plains",sand),"other biomes keep gravel while natural sand retains its matching palette");checks++;
  return checks;
 }

 private static void checkThreeLanes(BlockPos from,BlockPos to) {
  Map<String,Long> state=Map.of();String key="width-check";int cursor=8;
  Set<BlockPos> lanes=new HashSet<>();
  for(int side=-Roads.ROAD_HALF_WIDTH;side<=Roads.ROAD_HALF_WIDTH;side++) {
   BlockPos lane=Roads.laneCell(state,key,from,to,cursor,side);
   require(lane.getY()==from.getY(),"all lanes share the stored route height");
   lanes.add(lane);
  }
  require(lanes.size()==3,"one route cursor maps to three distinct walkable blocks");
  int spanX=lanes.stream().mapToInt(BlockPos::getX).max().orElseThrow()
   -lanes.stream().mapToInt(BlockPos::getX).min().orElseThrow();
  int spanZ=lanes.stream().mapToInt(BlockPos::getZ).max().orElseThrow()
   -lanes.stream().mapToInt(BlockPos::getZ).min().orElseThrow();
  require(spanX+spanZ==2,"three lane blocks form a contiguous straight cross-section");
 }

 private static void require(boolean condition,String message) {
  if(!condition)throw new IllegalStateException("Road width: "+message);
 }
}
