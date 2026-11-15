package de.villagerplus.dev;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.JobManager;
import de.villagerplus.job.JobState;
import de.villagerplus.job.Meals;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Villages;
import de.villagerplus.village.Wellbeing;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Development-only food-chain report (/vptest090 food): food stock, seeds, residents' satiety/activity, crop cells. */
public final class FoodDiag {
	private FoodDiag() { }

	private static String foodDetail(Villager v) {
		Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < v.getInventory().getContainerSize(); i++) {
			ItemStack s = v.getInventory().getItem(i);
			if (Meals.isFood(s) || s.is(net.minecraft.world.item.Items.WHEAT)) m.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
		}
		return m.toString();
	}

	public static int report(CommandSourceStack src) {
		ServerLevel level = src.getLevel();
		var ref = Villages.villageNear(level, BlockPos.containing(src.getPosition()), 400);
		if (ref == null) {
			ref = Villages.villageNear(level, new BlockPos(122, 69, -378), 400);
		}
		if (ref == null) { src.sendFailure(Component.literal("no village")); return 0; }
		var d = ref.data();
		StringBuilder sb = new StringBuilder();
		var residents = Villages.residents(level, ref, de.villagerplus.Config.VILLAGE_RADIUS, v -> true);
		var stores = Balance.storage(level, ref);
		Map<String, Integer> items = new LinkedHashMap<>();
		for (Container box : stores) for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack s = box.getItem(i);
			if (!s.isEmpty() && (Meals.isFood(s) || s.is(net.minecraft.world.item.Items.WHEAT) || s.is(net.minecraft.world.item.Items.WHEAT_SEEDS)
				|| s.is(net.minecraft.world.item.Items.BEETROOT_SEEDS) || s.is(net.minecraft.world.item.Items.MELON_SEEDS) || s.is(net.minecraft.world.item.Items.PUMPKIN_SEEDS))) {
				items.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
			}
		}
		sb.append("FOODDIAG t=").append(level.getGameTime()).append(" residents=").append(residents.size())
			.append(" farms=").append(d.farms).append(" hasPlan=").append(d.hasPlan()).append(" planType=").append(d.planType)
			.append(" nextFarm-now=").append(d.extra.nextFarm - level.getGameTime()).append(" stores=").append(stores.size())
			.append(" foodStock=").append(Balance.stock(stores, Balance.Kind.FOOD)).append(" target=").append(de.villagerplus.job.FarmerJob.foodTarget(residents.size()))
			.append(" items=").append(items).append("\n");
		long now = level.getGameTime();
		int hungry = 0, carriers = 0, farmers = 0;
		for (Villager v : residents) {
			VillagerMemory m = v.getAttached(VillagerPlus.MEMORY);
			if (m == null) continue;
			if (Wellbeing.of(level, v, m).satiety() < 25) hungry++;
			if (v.getVillagerData().profession().getRegisteredName().endsWith("farmer")) farmers++;
			for (int i = 0; i < v.getInventory().getContainerSize(); i++) if (Meals.isFood(v.getInventory().getItem(i))) { carriers++; break; }
		}
		sb.append("  SUMMARY t=").append(now).append(" hungry=").append(hungry).append(" residents=").append(residents.size()).append(" farmers=").append(farmers)
			.append(" foodCarriers=").append(carriers).append(" harvests=").append(de.villagerplus.job.FarmerJob.HARVESTS).append("\n");
		Map<String, Integer> all = new java.util.TreeMap<>();
		for (Container box : stores) for (int i = 0; i < box.getContainerSize(); i++) {
			ItemStack s = box.getItem(i);
			if (!s.isEmpty()) all.merge(BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount(), Integer::sum);
		}
		sb.append("  publicStock=").append(all).append("\n");
		for (Villager v : residents) {
			VillagerMemory mem = v.getAttached(VillagerPlus.MEMORY);
			if (mem == null) continue;
			var st = Wellbeing.of(level, v, mem);
			JobState js = JobManager.stateIfKnown(v);
			int carriedFood = 0;
			for (int i = 0; i < v.getInventory().getContainerSize(); i++) {
				ItemStack s = v.getInventory().getItem(i);
				if (Meals.isFood(s) || s.is(net.minecraft.world.item.Items.WHEAT)) carriedFood += s.getCount();
			}
			sb.append("  ").append(mem.name).append(" ").append(v.getVillagerData().profession().getRegisteredName().replace("minecraft:", ""))
				.append(" satiety=").append(st.satiety()).append(" mood=").append(st.mood()).append(" lastMeal=").append(mem.lastMeal == 0 ? "never" : String.valueOf(now - mem.lastMeal))
				.append(" carriedFood=").append(carriedFood).append(carriedFood > 0 ? foodDetail(v) : "").append(" act='").append(js == null ? "?" : js.activity).append("'").append("\n");
		}
		int farmland = 0, crops = 0, mature = 0, empty = 0;
		java.util.List<BlockPos> matureAt = new java.util.ArrayList<>();
		BlockPos b = ref.bell();
		for (int x = -90; x <= 90; x++) for (int z = -90; z <= 90; z++) {
			if (!level.hasChunkAt(b.offset(x, 0, z))) continue;
			for (int y = -6; y <= 8; y++) {
				BlockPos p = b.offset(x, y, z);
				BlockState bs = level.getBlockState(p);
				if (bs.is(Blocks.FARMLAND)) {
					farmland++;
					if (level.getBlockState(p.above()).isAir()) empty++;
				} else if (bs.getBlock() instanceof CropBlock crop) {
					crops++;
					if (crop.isMaxAge(bs)) { mature++; matureAt.add(p.immutable()); }
				}
			}
		}
		sb.append("  farmland=").append(farmland).append(" cropsPlanted=").append(crops).append(" mature=").append(mature).append(" emptyFarmland=").append(empty);
		sb.append("\n  matureAt=").append(matureAt.stream().limit(30).map(BlockPos::toShortString).toList());
		for (Villager v : residents) {
			if (!v.getVillagerData().profession().getRegisteredName().endsWith("farmer")) continue;
			VillagerMemory mem = v.getAttached(VillagerPlus.MEMORY);
			var job = v.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.JOB_SITE).map(g -> g.pos().toShortString()).orElse("none");
			sb.append("\n  farmer ").append(mem == null ? "?" : mem.name).append(" at ").append(v.blockPosition().toShortString()).append(" jobsite=").append(job)
				.append(" workSpots=").append(mem == null ? -1 : mem.workSpots.size());
			JobState fs = JobManager.stateIfKnown(v);
			if (fs != null) sb.append(" matWait=").append(fs.materialWaitUntil - now).append(" idle0=").append(fs.idleUntil[0] - now).append(" idle1=").append(fs.idleUntil[1] - now)
				.append(" cooldown=").append(fs.cooldownUntil - now).append(" target=").append(fs.target == null ? "-" : fs.target.toShortString());
		}
		String out = sb.toString();
		org.slf4j.LoggerFactory.getLogger("villagerplus").info(out);
		src.sendSuccess(() -> Component.literal(out), false);
		return 1;
	}
}
