package de.villagerplus.build;

import com.mojang.serialization.JsonOps;
import de.villagerplus.village.VillageData;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Focused checks for the honest pre-kitchen wait; no blocks or inventories are mutated. */
public final class PlanMaterialWaitChecks {
	private PlanMaterialWaitChecks() { }

	public static int run() {
		VillageData data = new VillageData();
		data.planType = "granary";
		data.planOrigin = Optional.of(new BlockPos(10, 70, 20));
		data.planStep = 7;
		data.wanted.add("minecraft:stone_bricks#4");
		SimpleContainer stock = new SimpleContainer(2);
		data.extra.planMaterialWait = PlanMaterialWait.forPlan(data, stock, false);
		check(data.extra.planMaterialWait.isPresent() && data.planStep == 7 && stock.isEmpty(), "shortage records a wait without changing the cursor or stock");
		var saved = VillageData.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow();
		VillageData loaded = VillageData.CODEC.parse(JsonOps.INSTANCE, saved).getOrThrow();
		check(loaded.extra.planMaterialWait.equals(data.extra.planMaterialWait) && loaded.planStep == 7, "wait and cursor survive reload");
		check(PlanMaterialWait.forPlan(loaded, stock, true).isEmpty(), "pre-kitchen diagnosis is removed when a kitchen exists");
		stock.setItem(0, new ItemStack(Items.STONE_BRICKS, 4));
		check(PlanMaterialWait.forPlan(loaded, stock, false).isEmpty(), "satisfied shortage does not wait");
		loaded.clearPlan();
		check(loaded.extra.planMaterialWait.isEmpty() && PlanMaterialWait.forPlan(loaded, stock, false).isEmpty(), "reset clears the saved wait");
		return 5;
	}

	private static void check(boolean ok, String message) {
		if (!ok) throw new IllegalStateException("PLAN MATERIAL WAIT CHECK FAILED: " + message);
	}
}
