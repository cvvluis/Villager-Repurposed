package de.villagerplus.build;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import de.villagerplus.job.JobUtil;

/** Stone variants as stand-ins for plain cobblestone block steps: payment, shown block, demand split and what stays cobblestone-only. */
public final class CobbleLikeChecks {
	private CobbleLikeChecks() { }

	public static int run() {
		int checks = 0;
		Step cobble = Step.block(BlockPos.ZERO, Blocks.COBBLESTONE);
		Step stairs = Step.block(BlockPos.ZERO, Blocks.COBBLESTONE_STAIRS);
		Step bricks = Step.block(BlockPos.ZERO, Blocks.STONE_BRICKS);
		check(CobbleLike.substitutable(cobble) && !CobbleLike.substitutable(stairs) && !CobbleLike.substitutable(bricks), "only a plain cobblestone block step is substitutable"); checks++;

		SimpleContainer none = new SimpleContainer(4);
		check(Builder.paymentFor(cobble, none) == null, "an empty stock pays nothing"); checks++;
		SimpleContainer cobbleOnly = new SimpleContainer(4);
		cobbleOnly.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		Builder.Payment plain = Builder.paymentFor(cobble, cobbleOnly);
		check(plain != null && plain.item() == Items.COBBLESTONE && plain.block() == Blocks.COBBLESTONE, "cobblestone still pays a cobblestone step when no variant is stocked"); checks++;

		SimpleContainer mixed = new SimpleContainer(4);
		mixed.setItem(0, new ItemStack(Items.COBBLESTONE, 3));
		mixed.setItem(1, new ItemStack(Items.ANDESITE, 2));
		mixed.setItem(2, new ItemStack(Items.DIORITE, 5));
		Builder.Payment variant = Builder.paymentFor(cobble, mixed);
		check(variant != null && variant.item() == Items.DIORITE && variant.count() == 1 && variant.block() == Blocks.DIORITE,
			"a cobblestone step is paid with the most plentiful variant first, keeping cobblestone for foundations and stairs"); checks++;
		check(CobbleLike.placed(cobble, variant.block(), cobble.make().apply(variant.block())).is(Blocks.DIORITE)
			&& CobbleLike.placed(cobble, Blocks.COBBLESTONE, cobble.make().apply(Blocks.COBBLESTONE)).is(Blocks.COBBLESTONE),
			"the wall shows the variant that was paid, a cobblestone payment still places cobblestone"); checks++;
		check(Builder.paymentFor(stairs, mixed) == null && Builder.paymentFor(bricks, mixed) == null,
			"stairs and other blocks are never paid with variants"); checks++;
		SimpleContainer tuffOnly = new SimpleContainer(2);
		tuffOnly.setItem(0, new ItemStack(Items.TUFF, 9));
		tuffOnly.setItem(1, new ItemStack(Items.GRAVEL, 9));
		check(Builder.paymentFor(cobble, tuffOnly) == null, "only andesite, diorite and granite count: tuff and gravel do not"); checks++;

		Map<Item, Integer> site = new LinkedHashMap<>();
		SimpleContainer publicStock = new SimpleContainer(4);
		publicStock.setItem(0, new ItemStack(Items.ANDESITE, 30));
		publicStock.setItem(1, new ItemStack(Items.GRANITE, 10));
		Map<Item, Integer> demand = CobbleLike.demand(site, 48, 48, publicStock);
		check(demand.size() == 3 && demand.get(Items.ANDESITE) == 30 && demand.get(Items.GRANITE) == 10 && demand.get(Items.COBBLESTONE) == 8,
			"a 48 block demand asks for the 40 variants the village holds and only 8 cobblestone from the mine"); checks++;
		site.put(Items.DIORITE, 20);
		site.put(Items.COBBLESTONE, 4);
		demand = CobbleLike.demand(site, 48, 48, new SimpleContainer(1));
		check(demand.size() == 1 && demand.get(Items.COBBLESTONE) == 24 && site.isEmpty(),
			"variants and cobblestone at the site both count against the demand"); checks++;
		site.put(Items.ANDESITE, 40);
		demand = CobbleLike.demand(site, 40, 10, new SimpleContainer(1));
		check(demand.size() == 1 && demand.get(Items.COBBLESTONE) == 30 && site.getOrDefault(Items.ANDESITE, 0) == 30,
			"foundation cobblestone (40 needed, 10 of it block steps) is never covered by variants");  checks++;
		site.clear();
		demand = CobbleLike.demand(site, 12, 12, null);
		check(demand.size() == 1 && demand.get(Items.COBBLESTONE) == 12, "without variants the whole demand stays cobblestone, exactly as before"); checks++;
		check(CobbleLike.variantCount(mixed) == 7 && JobUtil.count(mixed, CobbleLike::stone) == 10 && CobbleLike.variantFor(null, 1) == null,
			"stand-in counting covers variants only"); checks++;
		return checks;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("COBBLE LIKE CHECK FAILED: " + message);
	}
}
