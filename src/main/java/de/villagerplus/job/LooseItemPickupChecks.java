package de.villagerplus.job;

import com.mojang.authlib.GameProfile;
import de.villagerplus.VillagerMemory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.Vec3;

/** Checks the offered-item path against real entities in an entity-ticking server chunk. */
public final class LooseItemPickupChecks {
	private LooseItemPickupChecks() { }

	public static int run(ServerLevel level, BlockPos at) {
		if (!ButcherChecks.prepareEntityTickingFixture(level, at)) return -1;
		Map<BlockPos, BlockState> original = new LinkedHashMap<>();
		List<Entity> fixtures = new ArrayList<>();
		boolean oldMobGriefing = level.getGameRules().get(GameRules.MOB_GRIEFING);
		try {
			for (int dx = 0; dx <= 4; dx++) {
				BlockPos feet = at.east(dx);
				stage(level, original, feet.below(), Blocks.STONE.defaultBlockState());
				stage(level, original, feet, Blocks.AIR.defaultBlockState());
				stage(level, original, feet.above(), Blocks.AIR.defaultBlockState());
			}
			level.getGameRules().set(GameRules.MOB_GRIEFING, true, level.getServer());
			Vec3 villagerPosition = Vec3.atBottomCenterOf(at).add(0.3, 0, 0);
			Vec3 offerPosition = villagerPosition.add(1.25, 0, 0);
			Villager first = villager(level, at, villagerPosition, fixtures);
			Villager second = villager(level, at, villagerPosition, fixtures);
			var firstState = JobManager.state(first);
			var secondState = JobManager.state(second);
			var player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "PickupFixture"),
				ClientInformation.createDefault());
			player.setPos(villagerPosition);
			// This overload creates the same item entity with the same thrower and delay as the hotbar drop.
			ItemEntity offered = player.drop(new ItemStack(Items.COPPER_INGOT, 5), false, true);
			if (offered == null) throw new IllegalStateException("ITEM PICKUP: player throw did not create an entity");
			offered.setPos(offerPosition);
			fixtures.add(offered);
			long now = level.getGameTime();
			int interval = LooseItemPickup.OFFER_SCAN_INTERVAL;
			int checks = 0;
			checks += check(offered.getOwner() == player && offered.hasPickUpDelay(),
				"ordinary player throw carries a thrower and a pickup delay");
			checks += check(!LooseItemPickup.offerNearby(ctx(level, first, firstState, now)) && offered.getItem().getCount() == 5,
				"resident leaves a stack alone while its pickup delay is active");
			offered.setNoPickUpDelay();
			checks += check(!LooseItemPickup.offerNearby(ctx(level, first, firstState, now + interval - 1)),
				"an empty search waits before looking for a drop again");
			checks += check(second.canPickUpLoot(), "ordinary villager has vanilla pickup enabled");
			fill(first);
			first.getInventory().setItem(0, new ItemStack(Items.COPPER_INGOT, 62));
			WalkTarget walking = new WalkTarget(at.west(3), 0.45F, 1);
			first.getBrain().setMemory(MemoryModuleType.WALK_TARGET, walking);
			checks += check(LooseItemPickup.offerNearby(ctx(level, first, firstState, now + interval))
				&& offered.isAlive() && offered.getItem().getCount() == 3 && offered.getOwner() == player
				&& count(first, Items.COPPER_INGOT) == 64,
				"first resident inserts only what fits and leaves the same drop with its remaining three items");
			checks += check(first.getBrain().getMemory(MemoryModuleType.WALK_TARGET).orElse(null) == walking,
				"collecting an offer leaves the current walking target in place");
			checks += check(!LooseItemPickup.offerNearby(ctx(level, second, secondState, now + interval))
				&& offered.isAlive() && count(second, Items.COPPER_INGOT) == 0,
				"a second resident cannot steal a drop already claimed by the first resident");
			first.getInventory().setItem(1, ItemStack.EMPTY);
			checks += check(LooseItemPickup.offerNearby(ctx(level, first, firstState, now + interval + 1))
				&& !offered.isAlive() && count(first, Items.COPPER_INGOT) == 67,
				"the claiming resident collects the remaining items without loss or duplication");

			ItemEntity blocked = drop(level, player, offerPosition, fixtures);
			stage(level, original, at.east(), Blocks.STONE.defaultBlockState());
			stage(level, original, at.east().above(), Blocks.STONE.defaultBlockState());
			checks += check(!LooseItemPickup.offerNearby(ctx(level, second, secondState, now + interval * 2L)) && blocked.isAlive(),
				"resident cannot pick up through an obstruction");
			stage(level, original, at.east(), Blocks.AIR.defaultBlockState());
			stage(level, original, at.east().above(), Blocks.AIR.defaultBlockState());
			checks += check(!LooseItemPickup.offerNearby(ctx(level, first, firstState, now + interval * 2L)) && blocked.isAlive(),
				"a full inventory preserves the whole original stack");
			blocked.setTarget(player.getUUID());
			ItemEntity distant = drop(level, player, villagerPosition.add(4, 0, 0), fixtures);
			ItemEntity unoffered = drop(level, null, offerPosition, fixtures);
			checks += check(LooseItemPickup.offerNearby(ctx(level, second, secondState, now + interval * 3L))
				&& blocked.isAlive() && unoffered.isAlive()
				&& secondState.offeredItemTarget == distant
				&& second.getBrain().getMemory(MemoryModuleType.WALK_TARGET).isPresent(),
				"the resident ignores reserved/non-player drops, notices a player drop within eight blocks and walks toward it");
			second.setPos(distant.position());
			checks += check(LooseItemPickup.offerNearby(ctx(level, second, secondState, now + interval * 3L + 1))
				&& !distant.isAlive() && count(second, Items.GOLD_INGOT) == 1,
				"the resident collects the claimed drop after reaching it");
			JobUtil.collectDrops(ctx(level, second, secondState, now + interval * 3L), 5.0, 100);
			checks += check(blocked.isAlive() && !unoffered.isAlive()
				&& count(second, Items.GOLD_INGOT) == 2,
				"fresh job drops can still be collected without bypassing the player-throw rules");

			ItemEntity disabled = drop(level, player, offerPosition, fixtures);
			level.getGameRules().set(GameRules.MOB_GRIEFING, false, level.getServer());
			checks += check(!LooseItemPickup.offerNearby(ctx(level, second, secondState, now + interval * 5L)) && disabled.isAlive(),
				"pickup respects the world mob griefing setting");
			return checks;
		} finally {
			level.getGameRules().set(GameRules.MOB_GRIEFING, oldMobGriefing, level.getServer());
			for (Entity entity : fixtures) entity.discard();
			original.forEach((pos, state) -> level.setBlock(pos, state, Block.UPDATE_ALL));
			ButcherChecks.releaseEntityTickingFixture(level, at);
		}
	}

	private static Villager villager(ServerLevel level, BlockPos at, Vec3 position, List<Entity> fixtures) {
		Villager villager = EntityTypes.VILLAGER.spawn(level, at, EntitySpawnReason.COMMAND);
		if (villager == null) throw new IllegalStateException("ITEM PICKUP: fixture villager did not spawn");
		villager.addTag("vp090_fixture");
		villager.setNoAi(true);
		villager.setPos(position);
		fixtures.add(villager);
		return villager;
	}

	private static ItemEntity drop(ServerLevel level, ServerPlayer player, Vec3 position, List<Entity> fixtures) {
		ItemEntity drop = new ItemEntity(level, position.x, position.y, position.z, new ItemStack(Items.GOLD_INGOT));
		if (player != null) drop.setThrower(player);
		drop.setNoPickUpDelay();
		if (!level.addFreshEntity(drop)) throw new IllegalStateException("ITEM PICKUP: fixture drop did not spawn");
		fixtures.add(drop);
		return drop;
	}

	private static Ctx ctx(ServerLevel level, Villager villager, JobState state, long now) {
		return new Ctx(level, villager, state, new VillagerMemory(), null, now);
	}

	private static void fill(Villager villager) {
		for (int slot = 0; slot < villager.getInventory().getContainerSize(); slot++) {
			villager.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
		}
	}

	private static int count(Villager villager, Item item) {
		return JobUtil.count(villager.getInventory(), stack -> stack.is(item));
	}

	private static void stage(ServerLevel level, Map<BlockPos, BlockState> original, BlockPos pos, BlockState state) {
		if (level.getBlockEntity(pos) != null) throw new IllegalStateException("ITEM PICKUP: fixture overlaps a block entity");
		original.putIfAbsent(pos, level.getBlockState(pos));
		level.setBlock(pos, state, Block.UPDATE_ALL);
	}

	private static int check(boolean pass, String description) {
		if (!pass) throw new IllegalStateException("ITEM PICKUP: " + description);
		return 1;
	}
}
