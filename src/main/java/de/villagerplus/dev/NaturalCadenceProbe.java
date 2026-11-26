package de.villagerplus.dev;

import com.mojang.logging.LogUtils;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.Phase;
import de.villagerplus.village.VillageData;
import java.util.Map;
import java.util.TreeMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.block.entity.BellBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

/** Opt-in read-only cadence samples for natural development-server runs. */
public final class NaturalCadenceProbe {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long SAMPLE_INTERVAL = 100L;

	private NaturalCadenceProbe() { }

	public static void register() {
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			long tick = server.overworld().getGameTime();
			if (tick % SAMPLE_INTERVAL != 0) return;
			for (ServerLevel level : server.getAllLevels()) sampleLevel(level, tick);
		});
	}

	private static void sampleLevel(ServerLevel level, long serverTick) {
		Map<Long, BellBlockEntity> bells = new TreeMap<>();
		for (var entity : level.getAllEntities()) {
			if (!(entity instanceof Villager villager)) continue;
			var meeting = villager.getBrain().getMemory(MemoryModuleType.MEETING_POINT);
			if (meeting.isEmpty()) continue;
			GlobalPos global = meeting.get();
			if (!global.dimension().equals(level.dimension())) continue;
			BlockPos pos = global.pos();
			// The ready check is deliberately before block-entity access: diagnostics must not load chunks.
			if (!de.villagerplus.world.LoadedColumns.ready(level, pos)) continue;
			if (level.getBlockEntity(pos) instanceof BellBlockEntity bell) bells.put(pos.asLong(), bell);
		}
		long dayTime = level.getOverworldClockTime();
		String phase = Phase.of(dayTime).name();
		for (BellBlockEntity bell : bells.values()) {
			VillageData data = bell.getAttachedOrElse(VillagerPlus.VILLAGE, null);
			if (data == null) continue;
			for (VillageData.BuildProject project : data.buildProjects) {
				if (project == null || !project.active()) continue;
				String wanted = project.wanted().isEmpty() ? "-" : String.join(",", project.wanted());
				String site = project.haul().siteStock().isEmpty() ? "-" : siteStock(project.haul().siteStock());
				String cargo = project.haul().cargoManifest().isEmpty() ? "-" : String.join(",", project.haul().cargoManifest());
				BlockPos origin = project.origin();
				LOGGER.info("VPCADENCE v=1 dim={} bell={},{},{} tick={} day={} phase={} type={} origin={},{},{} step={} total=unknown wanted={} site={} cargo={}",
					level.dimension().identifier(), bell.getBlockPos().getX(), bell.getBlockPos().getY(), bell.getBlockPos().getZ(),
					serverTick, dayTime, phase, project.type(), origin.getX(), origin.getY(), origin.getZ(), project.step(),
					wanted, site, cargo);
			}
		}
	}

	private static String siteStock(java.util.List<ItemStack> stacks) {
		Map<String, Integer> counts = new TreeMap<>();
		for (ItemStack stack : stacks) {
			if (stack == null || stack.isEmpty()) continue;
			String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
			counts.merge(id, stack.getCount(), Integer::sum);
		}
		return counts.entrySet().stream().map(entry -> entry.getKey() + "#" + entry.getValue())
			.collect(java.util.stream.Collectors.joining(","));
	}
}
