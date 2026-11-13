package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageRef;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Synthetic checks for the {@link LamplighterJob} entry points (primary and secondary): a lamplighter in a dark village
 * fixture finds its own dark spot (no preset target), places one real torch or lantern taken from the public village chest,
 * leaves private chests alone, honours the 64 block spacing and the placement cooldown, and does nothing without public
 * stock. The fixture is a stone platform high above the natural terrain (so natural lights are outside the vertical
 * spacing window); everything is restored afterwards. Synthetic fixture: no natural evidence.
 */
public final class LamplighterJobChecks {
	private LamplighterJobChecks() { }

	public static int run(ServerLevel level, BlockPos origin) {
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		Villager worker = null;
		int checks = 0;
		try {
			// Load exactly the chunks that intersect the 77 block disk around the bell: every 64 block spacing disk of a candidate
			// within 12 blocks is then fully known, while the far probes (20+ blocks out) still reach an unloaded chunk
			// when the origin is chunk aligned (the dispatcher passes a multiple of 16), so they cannot find natural terrain.
			if (Math.floorMod(origin.getX(), 16) != 0 || Math.floorMod(origin.getZ(), 16) != 0) {
				throw new IllegalStateException("LAMPLIGHTER CHECK FAILED: origin must be chunk aligned");
			}
			for (int cx = Math.floorDiv(origin.getX() - 80, 16); cx <= Math.floorDiv(origin.getX() + 80, 16); cx++) {
				for (int cz = Math.floorDiv(origin.getZ() - 80, 16); cz <= Math.floorDiv(origin.getZ() + 80, 16); cz++) {
					long dx = Math.max(0, Math.max((long) cx * 16 - origin.getX(), origin.getX() - ((long) cx * 16 + 15)));
					long dz = Math.max(0, Math.max((long) cz * 16 - origin.getZ(), origin.getZ() - ((long) cz * 16 + 15)));
					if (dx * dx + dz * dz <= 77L * 77L) level.getChunk(cx, cz);
				}
			}
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = -16; dx <= 16; dx++) for (int dz = -16; dz <= 16; dz++) for (int dy = -2; dy <= 5; dy++) {
				BlockPos p = origin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.removeBlockEntity(p);
				level.setBlock(p, dy < 0 ? stone : air, 3);
			}
			// The terrain far below may hold structure lights, but only blocks within +-63 of the lamp height matter.
			VillageRef ref = Checks090.fixtureForEquipment(level, origin);
			Container publicStock = ref.chest(level);
			BlockPos privatePos = origin.west(3);
			level.setBlock(privatePos, Blocks.CHEST.defaultBlockState(), 3);
			level.getBlockEntity(privatePos).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			Container privateStock = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, privatePos);
			require(publicStock != null && privateStock != null, "fixture chests exist");
			publicStock.clearContent();
			privateStock.clearContent();
			privateStock.setItem(0, new ItemStack(Items.TORCH, 4));
			publicStock.setItem(0, new ItemStack(Items.TORCH, 3));

			worker = EntityTypes.VILLAGER.spawn(level, origin.east(6), EntitySpawnReason.COMMAND);
			if (worker == null) throw new IllegalStateException("LAMPLIGHTER CHECK FAILED: could not spawn lamplighter");
			worker.addTag("vp090_fixture");
			worker.setNoAi(true);
			worker.setInvulnerable(true);
			VillagerMemory memory = worker.getAttachedOrCreate(VillagerPlus.MEMORY);
			JobState state = new JobState();
			LamplighterJob job = new LamplighterJob();
			long now = level.getGameTime() + 1;

			BlockPos spot = Lighting.findDark(level, ref.bell(), 12);
			require(spot != null && Lighting.placeable(level, spot) && level.getBlockState(spot).isAir()
				&& Math.abs(spot.getX() - origin.getX()) <= 12 && Math.abs(spot.getZ() - origin.getZ()) <= 12,
				"the dark platform offers a placeable spot near the bell (found " + (spot == null ? "none" : spot.toShortString()) + ")"); checks++;
			stand(worker, spot);

			// A: primary entry, no preset target: one real torch from the public chest.
			int jobs = memory.jobsDone;
			require(job.primary(ctx(level, worker, state, memory, ref, now)) && level.getBlockState(spot).is(Blocks.TORCH),
				"primary places a torch at the dark spot it found itself"); checks++;
			require(count(publicStock, Items.TORCH) == 2 && count(privateStock, Items.TORCH) == 4 && memory.jobsDone == jobs + 1
				&& state.target == null && state.lightAt == now + 20,
				"exactly one public torch is used, the private chest is untouched, the cooldown starts"); checks++;

			// B: still cooling down: the entry points do nothing.
			require(!job.primary(ctx(level, worker, state, memory, ref, now + 5)) && !job.secondary(ctx(level, worker, state, memory, ref, now + 5))
				&& count(publicStock, Items.TORCH) == 2, "inside the placement cooldown neither entry point places a light"); checks++;

			// C: after the cooldown the 64 block spacing leaves no dark spot: no second torch, retry delayed.
			require(!job.secondary(ctx(level, worker, state, memory, ref, now + 25)) && count(publicStock, Items.TORCH) == 2
				&& state.lightAt == now + 25 + 200 && Lighting.findDark(level, ref.bell(), 12) == null,
				"a lit area is not lit again (spacing), and the next search is delayed"); checks++;

			// D: secondary entry places a torch as well once the spot is dark again.
			level.setBlock(spot, Blocks.AIR.defaultBlockState(), 3);
			state.lightAt = 0L;
			stand(worker, spot);
			require(job.secondary(ctx(level, worker, state, memory, ref, now + 300)) && level.getBlockState(spot).is(Blocks.TORCH)
				&& count(publicStock, Items.TORCH) == 1, "secondary delegates to the same lighting work"); checks++;

			// E: no public stock: private torches are no source, nothing is placed, the retry is delayed.
			level.setBlock(spot, Blocks.AIR.defaultBlockState(), 3);
			publicStock.clearContent();
			state.lightAt = 0L;
			require(!job.primary(ctx(level, worker, state, memory, ref, now + 600)) && level.getBlockState(spot).isAir()
				&& count(privateStock, Items.TORCH) == 4 && state.lightAt == now + 600 + 200,
				"without public stock nothing is placed and private torches stay untouched"); checks++;

			// F: a public lantern is placed upright, consumed once.
			publicStock.setItem(3, new ItemStack(Items.LANTERN, 2));
			state.lightAt = 0L;
			state.clearTarget();
			BlockPos lanternSpot = Lighting.findDark(level, ref.bell(), 12);
			require(lanternSpot != null, "a dark spot exists again for the lantern");
			stand(worker, lanternSpot);
			require(job.primary(ctx(level, worker, state, memory, ref, now + 900)) && level.getBlockState(lanternSpot).is(Blocks.LANTERN)
				&& count(publicStock, Items.LANTERN) == 1, "a public lantern is placed from stock when no torch exists"); checks++;

			// G: a private (owned) village chest is never lamp stock, even as the only chest.
			level.setBlock(lanternSpot, Blocks.AIR.defaultBlockState(), 3);
			publicStock.clearContent();
			publicStock.setItem(0, new ItemStack(Items.TORCH, 2));
			level.getBlockEntity(ref.chestPos(level)).setAttached(VillagerPlus.OWNER, UUID.randomUUID());
			state.lightAt = 0L;
			require(!job.primary(ctx(level, worker, state, memory, ref, now + 1200)) && level.getBlockState(lanternSpot).isAir()
				&& count(publicStock, Items.TORCH) == 2, "an owned village chest is not treated as public lamp stock"); checks++;
			return checks;
		} finally {
			if (worker != null) worker.discard();
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
		}
	}

	private static void stand(Villager worker, BlockPos spot) {
		worker.teleportTo(spot.getX() + 1.5, spot.getY(), spot.getZ() + 0.5);
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory, VillageRef ref, long now) {
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static int count(Container container, Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("LAMPLIGHTER CHECK FAILED: " + label);
	}
}
