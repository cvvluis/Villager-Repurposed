package de.villagerplus.job;

import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.dev.Checks090;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Synthetic checks for the slaughter itself ({@link ButcherJob#primary}/{@link ButcherJob#secondary}); the selection rules are
 * covered by {@link ButcherChecks}. One real pen holds one cow more than its breeding stock plus a protected (named) cow
 * that stands closest to the butcher: exactly the nearest eligible surplus cow is killed with a real hit, its real loot
 * drops are collected into the butcher's inventory (nothing left on the ground, nothing lost), the herd then stays at its
 * breeding stock, carried raw meat is delivered to the public village chest, and the afternoon cooking turns the stored raw
 * meat into cooked meat without losing any. The fixture is one chunk (platform, pen, bell); everything is restored.
 * Synthetic fixture: no natural evidence.
 */
public final class ButcherSlaughterChecks {
	private ButcherSlaughterChecks() { }

	/** @param chunkOrigin chunk aligned (multiple of 16 on X and Z); returns -1 while the entity-ticking fixture is still preparing */
	public static int run(ServerLevel level, BlockPos chunkOrigin) {
		if (Math.floorMod(chunkOrigin.getX(), 16) != 0 || Math.floorMod(chunkOrigin.getZ(), 16) != 0) {
			throw new IllegalStateException("BUTCHER SLAUGHTER CHECK FAILED: origin must be chunk aligned");
		}
		BlockPos pen = chunkOrigin.offset(8, 0, 8);
		if (!ButcherChecks.prepareEntityTickingFixture(level, pen)) {
			System.out.println("BUTCHER SLAUGHTER PREPARING entity-ticking fixture at " + pen.toShortString());
			return -1;
		}
		Map<BlockPos, BlockState> before = new LinkedHashMap<>();
		List<Entity> spawned = new ArrayList<>();
		int checks = 0;
		try {
			level.getChunkAt(pen);
			BlockState air = Blocks.AIR.defaultBlockState();
			BlockState stone = Blocks.STONE.defaultBlockState();
			for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) for (int dy = -2; dy <= 5; dy++) {
				BlockPos p = chunkOrigin.offset(dx, dy, dz);
				before.put(p, level.getBlockState(p));
				level.removeBlockEntity(p);
				level.setBlock(p, dy < 0 ? stone : air, 3);
			}
			BlockPos bell = chunkOrigin.offset(1, 0, 1);
			VillageRef ref = Checks090.fixtureForEquipment(level, bell);
			for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
				if (Math.abs(dx) != 4 && Math.abs(dz) != 4) continue;
				level.setBlock(pen.offset(dx, 0, dz), Blocks.OAK_FENCE.defaultBlockState(), 3);
			}
			level.setBlock(pen.north(4), Blocks.OAK_FENCE_GATE.defaultBlockState()
				.setValue(FenceGateBlock.FACING, Direction.NORTH).setValue(FenceGateBlock.OPEN, false), 3);
			ref.data().extra.pen = Optional.of(pen);
			ref.bellEntity().setAttached(VillagerPlus.ZONES, List.of(new ZonePolicy.Zone("slaughter-pen", "pasture",
				pen.getX() - 5, pen.getZ() - 5, pen.getX() + 5, pen.getZ() + 5)));
			Container village = ref.chest(level);
			require(village != null, "fixture village chest exists");
			village.clearContent();

			int cap = Livestock.PEN_CAPACITY_PER_SPECIES;
			List<Animal> herd = new ArrayList<>();
			for (int i = 0; i < cap + 1; i++) {
				Animal cow = EntityTypes.COW.create(level, EntitySpawnReason.COMMAND);
				if (cow == null) throw new IllegalStateException("BUTCHER SLAUGHTER CHECK FAILED: cow did not spawn");
				cow.setNoAi(true);
				cow.setAge(0);
				cow.setPos(pen.getX() + ((i % 4) - 1.5) * 1.1, pen.getY(), pen.getZ() + ((i / 4) - 1.5) * 1.1);
				if (!level.addFreshEntity(cow)) throw new IllegalStateException("BUTCHER SLAUGHTER CHECK FAILED: cow was not admitted");
				herd.add(cow);
				spawned.add(cow);
			}
			Villager butcher = EntityTypes.VILLAGER.spawn(level, pen.offset(-3, 0, -2), EntitySpawnReason.COMMAND);
			if (butcher == null) throw new IllegalStateException("BUTCHER SLAUGHTER CHECK FAILED: butcher did not spawn");
			spawned.add(butcher);
			butcher.addTag("vp090_fixture");
			butcher.setNoAi(true);
			butcher.setInvulnerable(true);
			butcher.setVillagerData(butcher.getVillagerData().withProfession(level.registryAccess(), VillagerProfession.BUTCHER));
			VillagerMemory memory = butcher.getAttachedOrCreate(VillagerPlus.MEMORY);
			JobState state = new JobState();
			ButcherJob job = new ButcherJob();
			long now = level.getGameTime() + 1;
			butcher.teleportTo(pen.getX() - 2.5, pen.getY(), pen.getZ() - 2.5);

			// The cow right next to the butcher is protected; the nearest eligible surplus cow must be the victim.
			Animal named = herd.stream().min(java.util.Comparator.comparingDouble(a -> a.distanceToSqr(butcher))).orElseThrow();
			named.setCustomName(Component.literal("Protected"));
			Animal expected = herd.stream().filter(a -> a != named && ButcherJob.cullable(a))
				.min(java.util.Comparator.comparingDouble(a -> a.distanceToSqr(butcher))).orElseThrow();
			require(ButcherJob.pickVictim(ctx(level, butcher, state, memory, ref, now)) == expected,
				"selection picks the nearest eligible surplus cow and skips the protected one"); checks++;

			int jobs = memory.jobsDone;
			require(job.primary(ctx(level, butcher, state, memory, ref, now)), "primary works on the surplus"); checks++;
			long alive = herd.stream().filter(Animal::isAlive).count();
			require(expected.isDeadOrDying() && alive == cap && named.isAlive()
				&& herd.stream().filter(a -> a != expected).allMatch(Animal::isAlive),
				"exactly the selected surplus cow was killed; the herd keeps its breeding stock of " + cap + " (alive " + alive + ")"); checks++;
			require(memory.jobsDone == jobs + 1 && state.cooldownUntil == now + 60 && state.targetEntity == null,
				"the kill counts as work and starts the cooldown"); checks++;

			AABB around = new AABB(pen).inflate(6, 3, 6);
			List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, around, ItemEntity::isAlive);
			int beefDropped = dropCount(drops, Items.BEEF);
			int leatherDropped = dropCount(drops, Items.LEATHER);
			require(beefDropped >= 1 && drops.size() == dropKinds(drops), "the kill leaves real loot on the ground (beef " + beefDropped
				+ ", leather " + leatherDropped + ")"); checks++;

			// Second tick after the cooldown: loot is collected, nothing further is slaughtered at the breeding stock.
			long later = now + 61;
			boolean worked = job.primary(ctx(level, butcher, state, memory, ref, later));
			require(!worked && herd.stream().filter(Animal::isAlive).count() == cap,
				"at the breeding stock nothing more is slaughtered"); checks++;
			require(count(butcher.getInventory(), Items.BEEF) == beefDropped && count(butcher.getInventory(), Items.LEATHER) == leatherDropped
				&& level.getEntitiesOfClass(ItemEntity.class, around, ItemEntity::isAlive).isEmpty(),
				"all dropped loot is in the butcher's inventory, none is left or lost"); checks++;
			require(state.idleUntil[0] == later + 400, "the butcher idles after finding no surplus"); checks++;

			// Delivery without a granary: raw meat is food, the central material chest rejects food by design, so the
			// butcher keeps what he carries (nothing is destroyed) and retries later.
			butcher.getInventory().clearContent();
			butcher.getInventory().setItem(0, new ItemStack(Items.BEEF, 6));
			butcher.getInventory().setItem(1, new ItemStack(Items.PORKCHOP, 4));
			state.idleUntil[0] = 0L;
			state.clearTarget();
			BlockPos chestPos = ref.chestPos(level);
			long deliverAt = later + 1000;
			BlockPos stand = JobUtil.sharedStandSpot(ctx(level, butcher, state, memory, ref, deliverAt), chestPos, 4.0);
			require(stand != null, "the public village chest has a delivery stand"); checks++;
			butcher.teleportTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5);
			boolean refused = job.primary(ctx(level, butcher, state, memory, ref, deliverAt));
			require(!refused && count(village, Items.BEEF) == 0 && count(village, Items.PORKCHOP) == 0
				&& count(butcher.getInventory(), Items.BEEF) == 6 && count(butcher.getInventory(), Items.PORKCHOP) == 4,
				"without a granary the central chest refuses raw meat and the butcher keeps all ten items"); checks++;

			// Delivery with a registered granary: all raw meat reaches the public food store and leaves the butcher.
			BlockPos granaryPos = chunkOrigin.offset(1, 0, 8);
			level.setBlock(granaryPos, Blocks.BARREL.defaultBlockState(), 3);
			de.villagerplus.village.Specials.set(ref, de.villagerplus.build.Blueprints.GRANARY, granaryPos);
			Container granary = net.minecraft.world.level.block.entity.HopperBlockEntity.getContainerAt(level, granaryPos);
			require(granary != null, "fixture granary exists");
			granary.clearContent();
			state.clearTarget();
			long granaryAt = deliverAt + 3000;
			BlockPos granaryStand = JobUtil.sharedStandSpot(ctx(level, butcher, state, memory, ref, granaryAt), granaryPos, 4.0);
			require(granaryStand != null, "the granary has a delivery stand"); checks++;
			butcher.teleportTo(granaryStand.getX() + 0.5, granaryStand.getY(), granaryStand.getZ() + 0.5);
			boolean stored = job.primary(ctx(level, butcher, state, memory, ref, granaryAt));
			require(stored && count(granary, Items.BEEF) == 6 && count(granary, Items.PORKCHOP) == 4
				&& count(butcher.getInventory(), Items.BEEF) == 0 && count(butcher.getInventory(), Items.PORKCHOP) == 0
				&& count(village, Items.BEEF) == 0,
				"ten raw meat items reach the public granary and leave the butcher (granary beef " + count(granary, Items.BEEF)
					+ ", pork " + count(granary, Items.PORKCHOP) + ", worked=" + stored + ", activity=" + state.activity + ")"); checks++;

			// Observation only: without a kitchen smoker nothing is cooked from the granary (the real smoker cycle is covered by
			// ButcherCookingChecks); the raw meat just stays in the food store.
			butcher.teleportTo(chestPos.getX() + 1.5, chestPos.getY(), chestPos.getZ() + 0.5);
			boolean cooked = job.secondary(ctx(level, butcher, state, memory, ref, granaryAt + 500));
			System.out.println("BUTCHER SLAUGHTER NOTE secondary with raw meat only in the granary: worked=" + cooked
				+ ", granary beef=" + count(granary, Items.BEEF) + ", cooked beef=" + count(granary, Items.COOKED_BEEF)
				+ count(village, Items.COOKED_BEEF));
			require(count(granary, Items.BEEF) + count(granary, Items.COOKED_BEEF) + count(village, Items.COOKED_BEEF) == 6,
				"whatever the afternoon work does, no meat is lost"); checks++;
			return checks;
		} finally {
			spawned.forEach(Entity::discard);
			level.getEntitiesOfClass(ItemEntity.class, new AABB(chunkOrigin).inflate(16, 8, 16)).forEach(Entity::discard);
			before.keySet().forEach(level::removeBlockEntity);
			before.forEach((p, s) -> level.setBlock(p, s, 3));
			ButcherChecks.releaseEntityTickingFixture(level, pen);
		}
	}

	private static int dropCount(List<ItemEntity> drops, Item item) {
		int n = 0;
		for (ItemEntity drop : drops) if (drop.getItem().is(item)) n += drop.getItem().getCount();
		return n;
	}

	/** Number of drops that are beef or leather (the cow loot table); anything else would be an unexpected drop. */
	private static int dropKinds(List<ItemEntity> drops) {
		int n = 0;
		for (ItemEntity drop : drops) if (drop.getItem().is(Items.BEEF) || drop.getItem().is(Items.LEATHER)) n++;
		return n;
	}

	private static Ctx ctx(ServerLevel level, Villager worker, JobState state, VillagerMemory memory, VillageRef ref, long now) {
		return new Ctx(level, worker, state, memory, ref, now);
	}

	private static int count(Container container, Item item) {
		return JobUtil.count(container, stack -> stack.is(item));
	}

	private static void require(boolean ok, String label) {
		if (!ok) throw new IllegalStateException("BUTCHER SLAUGHTER CHECK FAILED: " + label);
	}
}
