package de.villagerplus.build;

import de.villagerplus.village.VillageData;
import de.villagerplus.village.VillageStyle;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;

/** Focused server-side checks for biome fallback choices and their material/state invariants. */
public final class SupplyChecks {
	private SupplyChecks() { }

	public static int run() {
		int checks = 0;
		check(Supply.obtainable(Blocks.STRIPPED_BIRCH_LOG),
			"stripped birch logs remain a supported exact construction material"); checks++;
		check(Supply.obtainable(Blocks.BIRCH_PLANKS),
			"birch planks are accepted through the vanilla planks tag"); checks++;
		check(Supply.obtainable(Blocks.CRAFTING_TABLE) && Supply.obtainable(Blocks.SCAFFOLDING),
			"temporary construction workbench and scaffolding are valid supply materials"); checks++;
		check(Supply.obtainable(Blocks.BARREL) && Supply.obtainable(Blocks.OAK_PLANKS) && Supply.obtainable(Blocks.OAK_SLAB),
			"vanilla barrel role markers are obtainable from the 26.2 planks-and-wooden-slabs recipe"); checks++;
		VillageStyle unavailable = new VillageStyle(Blocks.BRICKS, Blocks.BRICKS, Blocks.BRICKS,
			Blocks.BRICK_STAIRS, Blocks.IRON_DOOR, Blocks.IRON_BARS);

		VillageStyle desert = palette(unavailable, "desert");
		check(desert.wall() == Blocks.SANDSTONE && desert.floor() == Blocks.SMOOTH_SANDSTONE
			&& desert.roof() == Blocks.CUT_SANDSTONE && desert.stairs() == Blocks.SANDSTONE_STAIRS,
			"desert fallback selects obtainable sandstone components"); checks++;
		check(Supply.substitute(Blocks.BRICKS, unavailable, id("desert")) == Blocks.SANDSTONE,
			"unobtainable template wall resolves to the desert material"); checks++;
		check(Supply.substitute(Blocks.BRICK_STAIRS, unavailable, id("desert")) == Blocks.SANDSTONE_STAIRS,
			"unobtainable template stairs keep their shape in the desert palette"); checks++;
		check(path(Supply.substitute(Blocks.PURPUR_SLAB, unavailable, id("desert"))).equals("smooth_sandstone_slab"),
			"unobtainable template slab follows the desert floor"); checks++;
		check(path(Supply.substitute(Blocks.NETHER_BRICK_WALL, unavailable, id("desert"))).equals("sandstone_wall"),
			"unobtainable template wall shape follows the desert wall material"); checks++;
		check(Supply.substitute(Blocks.IRON_DOOR, unavailable, id("desert")) == Blocks.OAK_DOOR
			&& Supply.substitute(Blocks.IRON_TRAPDOOR, unavailable, id("desert")) == Blocks.OAK_TRAPDOOR,
			"unobtainable doors retain their structure using the safe fallback wood"); checks++;
		check(Supply.substitute(Blocks.NETHER_BRICK_FENCE, unavailable, id("desert")) == Blocks.OAK_FENCE,
			"unobtainable fence resolves to the available village fence"); checks++;
		check(Supply.substitute(Blocks.IRON_BARS, unavailable, id("desert")) == Blocks.GLASS_PANE,
			"unobtainable bars become a supported transparent shape"); checks++;
		List<Block> decorative = List.of(Blocks.SOUL_LANTERN, Blocks.OAK_LEAVES, Blocks.VINE,
			requiredBlock("white_banner"), requiredBlock("red_carpet"), Blocks.FLOWER_POT, Blocks.POPPY);
		check(Supply.substitute(Blocks.SOUL_LANTERN, unavailable, id("desert")) == Blocks.TORCH
			&& decorative.stream().map(block -> Supply.substitute(block, unavailable, id("desert")))
				.allMatch(block -> block != null && Supply.obtainable(block)),
			"source-template lantern and previously omitted decorations retain paid positions with obtainable replacements"); checks++;

		VillageStyle taiga = palette(unavailable, "old_growth_pine_taiga");
		VillageStyle savanna = palette(unavailable, "windswept_savanna");
		VillageStyle badlands = palette(unavailable, "wooded_badlands");
		VillageStyle jungle = palette(unavailable, "jungle");
		VillageStyle bamboo = palette(unavailable, "bamboo_jungle");
		VillageStyle mangrove = palette(unavailable, "mangrove_swamp");
		VillageStyle swamp = palette(unavailable, "swamp");
		VillageStyle cherry = palette(unavailable, "cherry_grove");
		VillageStyle birch = palette(unavailable, "old_growth_birch_forest");
		VillageStyle darkForest = palette(unavailable, "dark_forest");
		VillageStyle paleGarden = palette(unavailable, "pale_garden");
		VillageStyle plains = palette(unavailable, "plains");
		check(taiga.floor() == Blocks.SPRUCE_PLANKS && taiga.stairs() == Blocks.SPRUCE_STAIRS,
			"cold taiga fallback selects spruce components"); checks++;
		check(savanna.floor() == Blocks.ACACIA_PLANKS && savanna.fence() == Blocks.ACACIA_FENCE,
			"savanna fallback selects acacia components"); checks++;
		check(badlands.wall() == Blocks.RED_SANDSTONE && badlands.stairs() == Blocks.RED_SANDSTONE_STAIRS,
			"badlands fallback selects red sandstone before generic desert rules"); checks++;
		check(jungle.floor() == Blocks.JUNGLE_PLANKS && bamboo.floor() == block("bamboo_planks"),
			"jungle variants select their local wood family"); checks++;
		check(mangrove.floor() == block("mangrove_planks") && swamp.floor() == Blocks.OAK_PLANKS
			&& swamp.wall() == Blocks.MOSSY_COBBLESTONE,
			"mangrove and ordinary swamp use distinct supported palettes"); checks++;
		check(cherry.floor() == block("cherry_planks") && birch.floor() == Blocks.BIRCH_PLANKS
			&& darkForest.floor() == Blocks.DARK_OAK_PLANKS && paleGarden.floor() == block("pale_oak_planks"),
			"temperate forest variants use their corresponding available wood"); checks++;
		check(plains.floor() == Blocks.OAK_PLANKS && plains.wall() == Blocks.COBBLESTONE,
			"unknown biomes use a conservative obtainable default"); checks++;
		check(allObtainable(desert) && allObtainable(taiga) && allObtainable(savanna) && allObtainable(badlands)
			&& allObtainable(jungle) && allObtainable(bamboo) && allObtainable(mangrove) && allObtainable(swamp)
			&& allObtainable(cherry) && allObtainable(birch) && allObtainable(darkForest) && allObtainable(paleGarden)
			&& allObtainable(plains), "every biome palette component is within the obtainable supply set"); checks++;

		VillageStyle detected = new VillageStyle(Blocks.COBBLESTONE, Blocks.SPRUCE_PLANKS, Blocks.SPRUCE_PLANKS,
			Blocks.SPRUCE_STAIRS, Blocks.SPRUCE_DOOR, Blocks.SPRUCE_FENCE);
		check(Supply.withBiomeFallback(detected, id("desert")).equals(detected),
			"obtainable CTOV style components take precedence over biome defaults"); checks++;
		VillageStyle partial = new VillageStyle(Blocks.COBBLESTONE, Blocks.BRICKS, Blocks.SPRUCE_PLANKS,
			Blocks.BRICK_STAIRS, Blocks.IRON_DOOR, Blocks.SPRUCE_FENCE);
		VillageStyle resolved = Supply.withBiomeFallback(partial, id("desert"));
		check(resolved.wall() == Blocks.COBBLESTONE && resolved.floor() == Blocks.SMOOTH_SANDSTONE
			&& resolved.roof() == Blocks.SPRUCE_PLANKS && resolved.stairs() == Blocks.SANDSTONE_STAIRS
			&& resolved.door() == Blocks.OAK_DOOR && resolved.fence() == Blocks.SPRUCE_FENCE,
			"fallback repairs only unavailable style components"); checks++;

		Palette unavailablePalette = unavailablePalette();
		check(Builder.paletteForPlan(unavailablePalette, id("desert"), 0) == unavailablePalette,
			"generated plans keep their detected palette until the fallback variant is enabled"); checks++;
		Palette generatedFallback = Builder.paletteForPlan(unavailablePalette, id("desert"), 2);
		check(generatedFallback.wall() == Blocks.SANDSTONE && generatedFallback.wall2() == Blocks.SANDSTONE
			&& generatedFallback.frame() == Blocks.OAK_LOG && generatedFallback.base() == Blocks.SANDSTONE
			&& generatedFallback.roofFull() == Blocks.CUT_SANDSTONE
			&& generatedFallback.roofStair() == Blocks.SANDSTONE_STAIRS
			&& generatedFallback.roofSlab() == block("smooth_sandstone_slab")
			&& generatedFallback.window() == Blocks.GLASS_PANE && generatedFallback.floor() == Blocks.SMOOTH_SANDSTONE
			&& generatedFallback.door() == Blocks.OAK_DOOR && generatedFallback.fence() == Blocks.OAK_FENCE
			&& obtainable(generatedFallback),
			"generated-plan fallback applies the desert palette to every unavailable building role"); checks++;
		List<Step> originalPlan = Architect.build(Architect.design(Architect.Kind.GRANARY, 19L), unavailablePalette,
			BlockPos.ZERO, Direction.SOUTH);
		List<Step> fallbackPlan = Architect.build(Architect.design(Architect.Kind.GRANARY, 19L), generatedFallback,
			BlockPos.ZERO, Direction.SOUTH);
		check(sameGeometry(originalPlan, fallbackPlan),
			"generated-plan material substitution keeps step order, cursor length and placement geometry stable"); checks++;
		List<Step> inProgressFallback = Builder.preservePlanGeometry(originalPlan, fallbackPlan, 5);
		check(inProgressFallback != null && inProgressFallback.size() == originalPlan.size()
			&& inProgressFallback.subList(0, 5).equals(originalPlan.subList(0, 5))
			&& sameGeometry(originalPlan, inProgressFallback),
			"in-progress palette switch retains completed prefix, full step order and cursor geometry"); checks++;

		BlockState sourceStairs = Blocks.BRICK_STAIRS.defaultBlockState()
			.setValue(StairBlock.FACING, Direction.EAST).setValue(StairBlock.HALF, Half.TOP);
		BlockState replacementStairs = Supply.transfer(sourceStairs,
			Supply.substitute(sourceStairs.getBlock(), unavailable, id("desert")));
		check(replacementStairs.getBlock() == Blocks.SANDSTONE_STAIRS
			&& replacementStairs.getValue(StairBlock.FACING) == Direction.EAST
			&& replacementStairs.getValue(StairBlock.HALF) == Half.TOP,
			"fallback transfers stair orientation and upper/lower half"); checks++;

		VillageData plan = new VillageData();
		plan.planStep = 5;
		SimpleContainer stock = new SimpleContainer(1);
		stock.setItem(0, new ItemStack(Blocks.BRICKS.asItem(), 3));
		int stockBefore = stock.getItem(0).getCount();
		Block paidMaterial = Supply.substitute(Blocks.BRICKS, unavailable, id("desert"));
		Step paidReplacement = Step.block(BlockPos.ZERO, paidMaterial);
		check(paidReplacement.cost() == 1 && paidReplacement.item() == paidMaterial.asItem(),
			"substituted block remains an explicitly paid material step"); checks++;
		check(stock.getItem(0).getCount() == stockBefore && plan.planStep == 5 && plan.planVariant == 0,
			"palette selection leaves inventory and the stored plan cursor/variant unchanged"); checks++;
		return checks;
	}

	/** Reuse the actual generated-plan selector after the in-world caravan callback flips its persisted variant bit. */
	public static void assertGeneratedFallbackSelected(int planVariant) {
		Palette selected = Builder.paletteForPlan(unavailablePalette(), id("desert"), planVariant);
		check((planVariant & 2) != 0 && selected.wall() == Blocks.SANDSTONE
			&& selected.floor() == Blocks.SMOOTH_SANDSTONE && selected.roofStair() == Blocks.SANDSTONE_STAIRS,
			"the real caravan fallback variant selects the generated-plan biome palette");
	}

	private static Palette unavailablePalette() {
		return new Palette(Blocks.BRICKS, Blocks.NETHER_BRICKS, Blocks.IRON_BLOCK,
			Blocks.BRICKS, Blocks.BRICKS, Blocks.BRICK_STAIRS, Blocks.PURPUR_SLAB, Blocks.BRICKS,
			Blocks.BRICKS, Blocks.IRON_DOOR, Blocks.NETHER_BRICK_FENCE);
	}

	private static VillageStyle palette(VillageStyle detected, String biome) {
		return Supply.withBiomeFallback(detected, id(biome));
	}

	private static Identifier id(String biome) {
		return Identifier.withDefaultNamespace(biome);
	}

	private static Block block(String path) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(path)).orElse(Blocks.COBBLESTONE);
	}

	private static Block requiredBlock(String path) {
		return BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace(path))
			.orElseThrow(() -> new IllegalStateException("Missing expected vanilla block registration: minecraft:" + path));
	}

	private static String path(Block block) {
		return BuiltInRegistries.BLOCK.getKey(block).getPath();
	}

	private static boolean allObtainable(VillageStyle style) {
		return Supply.obtainable(style.wall()) && Supply.obtainable(style.floor()) && Supply.obtainable(style.roof())
			&& Supply.obtainable(style.stairs()) && Supply.obtainable(style.door()) && Supply.obtainable(style.fence());
	}

	private static boolean obtainable(Palette palette) {
		return Supply.obtainable(palette.wall()) && Supply.obtainable(palette.wall2())
			&& (palette.frame() == null || Supply.obtainable(palette.frame())) && Supply.obtainable(palette.base())
			&& Supply.obtainable(palette.roofFull()) && Supply.obtainable(palette.roofStair())
			&& (palette.roofSlab() == null || Supply.obtainable(palette.roofSlab())) && Supply.obtainable(palette.window())
			&& Supply.obtainable(palette.floor()) && Supply.obtainable(palette.door()) && Supply.obtainable(palette.fence());
	}

	private static boolean sameGeometry(List<Step> before, List<Step> after) {
		if (before.size() != after.size()) return false;
		for (int i = 0; i < before.size(); i++) {
			Step a = before.get(i), b = after.get(i);
			if (!a.pos().equals(b.pos()) || a.cost() != b.cost() || a.free() != b.free()
				|| a.optional() != b.optional() || !java.util.Objects.equals(a.linkedTo(), b.linkedTo())) return false;
		}
		return true;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("SUPPLY CHECK FAILED: " + message);
	}
}
