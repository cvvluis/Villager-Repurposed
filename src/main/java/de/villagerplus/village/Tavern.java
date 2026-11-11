package de.villagerplus.village;

import de.villagerplus.VillagerPlus;
import de.villagerplus.build.Blueprints;
import de.villagerplus.job.Ctx;
import de.villagerplus.job.JobUtil;
import de.villagerplus.job.Meals;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Optional evening stop at a generated Village Taverns structure. */
public final class Tavern {
	private static final String MOD_ID = "village_taverns";
	private static final Identifier TAVERN_BARREL = Identifier.fromNamespaceAndPath(MOD_ID, "barrel");
	private static final Set<Identifier> TAVERN_TEMPLATES = Set.of(
		Identifier.fromNamespaceAndPath(MOD_ID, "village/plains/tavern"),
		Identifier.fromNamespaceAndPath(MOD_ID, "village/desert/tavern"),
		Identifier.fromNamespaceAndPath(MOD_ID, "village/savanna/tavern"),
		Identifier.fromNamespaceAndPath(MOD_ID, "village/snowy/tavern"),
		Identifier.fromNamespaceAndPath(MOD_ID, "village/taiga/tavern"));
	private record StartKey(Identifier id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }
	private record CacheKey(Identifier dimension, BlockPos bell) { }
	private record Found(BlockPos marker, BlockPos stand, long checkedAt) { }
	private record Payment(Container source, int slot, net.minecraft.world.item.ItemStack before) { }
	static record TavernSpot(BlockPos marker, BlockPos stand) { }
	private static final Map<CacheKey, Found> CACHE = new HashMap<>();
	private static final long RESCAN_TICKS = 1200;

	private Tavern() { }

	/** true means the villager is travelling, being served, or waiting for its service cooldown. */
	public static boolean visit(Ctx c) {
		VillageRef ref = c.village();
		// Leave first-meal initialization to the normal household routine; hungry() initializes an unset clock.
		if (ref == null || c.mem().lastMeal == 0 || !Meals.hungry(c)) return false;
		// Do not search the world unless the villager can afford a meal that is actually in stock.
		if (!canAfford(c) || !hasPantryFood(c.level(), ref)) return false;
		TavernSpot tavern = find(c.level(), ref, c.v(), c.now());
		if (tavern == null || tavern.stand() == null) return false;

		c.setActivity("geht zur Taverne");
		if (!JobUtil.approach(c, tavern.stand(), 2.5)) {
			// JobUtil.approach() gives up after repeated failed paths. Let the evening routine fall back home then.
			return !(c.st().walkStart == 0L && c.st().cooldownUntil > c.now());
		}

		c.setActivity("isst in der Taverne");
		List<Container> pantry = Specials.containers(c.level(), ref, Blueprints.GRANARY);
		for (Container box : pantry) {
			if (serve(c, box)) {
				c.mem().jobsDone++;
				c.st().cooldownUntil = c.now() + 200;
				return true;
			}
		}
		return false;
	}

	/** Residents may spend emeralds from their own remembered home chest as well as their carried inventory. */
	static boolean canAfford(Ctx c) {
		for (Container wallet : Market.wallet(c.level(), c.v())) {
			if (JobUtil.count(wallet, stack -> stack.is(Items.EMERALD)) > 0) return true;
		}
		return false;
	}

	/** Charges once for a real prepared meal; failed service refunds the resident's emerald to its original slot. */
	static boolean serve(Ctx c, Container pantry) {
		Payment payment = takeMealPayment(c);
		if (payment == null) return false;
		if (!Meals.eatFrom(c, pantry, stack -> Meals.isPreparedFood(c.level(), stack))) {
			payment.source().setItem(payment.slot(), payment.before().copy());
			payment.source().setChanged();
			return false;
		}
		long revenue = c.village().bellEntity().getAttachedOrElse(VillagerPlus.TAVERN_REVENUE, 0L);
		if (revenue < Long.MAX_VALUE) c.village().bellEntity().setAttached(VillagerPlus.TAVERN_REVENUE, revenue + 1L);
		c.village().dirty();
		return true;
	}

	private static Payment takeMealPayment(Ctx c) {
		for (Container wallet : Market.wallet(c.level(), c.v())) {
			for (int slot = 0; slot < wallet.getContainerSize(); slot++) {
				var stack = wallet.getItem(slot);
				if (!stack.is(Items.EMERALD)) continue;
				net.minecraft.world.item.ItemStack before = stack.copy();
				if (wallet.removeItem(slot, 1).isEmpty()) continue;
				wallet.setChanged();
				return new Payment(wallet, slot, before);
			}
		}
		return null;
	}

	private static boolean hasPantryFood(net.minecraft.server.level.ServerLevel level, VillageRef ref) {
		for (Container box : Specials.containers(level, ref, Blueprints.GRANARY)) {
			if (JobUtil.count(box, stack -> Meals.isPreparedFood(level, stack)) > 0) return true;
		}
		return false;
	}

	/** Package-visible for the E02 check; returns only a marker inside a valid loaded village start. */
	static TavernSpot find(net.minecraft.server.level.ServerLevel level, VillageRef ref, Villager villager, long now) {
		if (ref == null || !isVillageTavernsLoaded()) return null;
		CacheKey key = new CacheKey(level.dimension().identifier(), ref.bell());
		Found cached = CACHE.get(key);
		if (cached != null && now >= cached.checkedAt() && now - cached.checkedAt() < RESCAN_TICKS) {
			if (markerStillPresent(level, cached.marker()) && reachableFloor(level, villager, cached.stand())) {
				return new TavernSpot(cached.marker(), cached.stand());
			}
			CACHE.remove(key);
		}

		BlockPos center = Market.of(ref).plaza().orElse(ref.bell());
		int minChunkX = (center.getX() - 64) >> 4, maxChunkX = (center.getX() + 64) >> 4;
		int minChunkZ = (center.getZ() - 64) >> 4, maxChunkZ = (center.getZ() + 64) >> 4;
		var structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
		Block markerBlock = BuiltInRegistries.BLOCK.getOptional(TAVERN_BARREL).orElse(null);
		Set<StartKey> visited = new HashSet<>();
		TavernSpot markerWithoutRoute = null;
		TavernSpot best = null;
		double bestDistance = Double.MAX_VALUE;
		for (int cx = minChunkX; cx <= maxChunkX; cx++) for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
			var chunk = level.getChunkSource().getChunkNow(cx, cz);
			if (chunk == null) continue;
			for (var entry : chunk.getAllStarts().entrySet()) {
				StructureStart start = entry.getValue();
				if (!start.isValid()) continue;
				Identifier id = structures.getKey(entry.getKey());
				if (id == null || !isVillageStructure(id)) continue;
				BoundingBox bounds = start.getBoundingBox();
				StartKey signature = new StartKey(id, bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
				if (!visited.add(signature)) continue;
				BlockPos marker = markerInLoadedPart(level, bounds, center, markerBlock);
				if (marker == null || marker.distSqr(villager.blockPosition()) > 96 * 96) continue;
				if (markerWithoutRoute == null) markerWithoutRoute = new TavernSpot(marker, null);
				BlockPos stand = reachableStand(level, villager, marker);
				if (stand == null) continue;
				double distance = stand.distSqr(villager.blockPosition());
				if (distance < bestDistance) {
					best = new TavernSpot(marker, stand);
					bestDistance = distance;
				}
			}
		}

		// Only a verified mod marker inside a loaded, valid village StructureStart is cached.
		if (best != null && markerStillPresent(level, best.marker())) {
			CACHE.put(key, new Found(best.marker(), best.stand(), now));
			if (CACHE.size() > 256) CACHE.entrySet().removeIf(e -> now < e.getValue().checkedAt() || now - e.getValue().checkedAt() > RESCAN_TICKS * 4);
		}
		return best == null ? markerWithoutRoute : best;
	}

	private static BlockPos markerInLoadedPart(net.minecraft.server.level.ServerLevel level, BoundingBox bounds, BlockPos center, Block markerBlock) {
		int minX = Math.max(bounds.minX(), center.getX() - 64), maxX = Math.min(bounds.maxX(), center.getX() + 64);
		int minZ = Math.max(bounds.minZ(), center.getZ() - 64), maxZ = Math.min(bounds.maxZ(), center.getZ() + 64);
		if (minX > maxX || minZ > maxZ) return null;
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
			if (level.getChunkSource().getChunkNow(cx, cz) == null) continue;
			int x0 = Math.max(minX, cx << 4), x1 = Math.min(maxX, (cx << 4) + 15);
			int z0 = Math.max(minZ, cz << 4), z1 = Math.min(maxZ, (cz << 4) + 15);
			for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
				for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					if (level.getBlockState(pos).getBlock() == markerBlock) return pos;
				}
			}
		}
		return null;
	}

	private static boolean isVillageStructure(Identifier id) {
		return id.getPath().toLowerCase(java.util.Locale.ROOT).contains("village");
	}

	private static boolean markerStillPresent(net.minecraft.server.level.ServerLevel level, BlockPos marker) {
		return level.hasChunkAt(marker) && isTavernMarker(level.getBlockState(marker));
	}

	private static BlockPos reachableStand(net.minecraft.server.level.ServerLevel level, Villager villager, BlockPos marker) {
		for (int radius = 1; radius <= 4; radius++) {
			for (int dy = -1; dy <= 1; dy++) for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
				if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
				BlockPos feet = marker.offset(dx, dy, dz);
				if (reachableFloor(level, villager, feet)) return feet;
			}
		}
		return null;
	}

	/** A tavern destination must be a supported, empty feet/head position with an actual navigation path. */
	static boolean reachableFloor(net.minecraft.server.level.ServerLevel level, Villager villager, BlockPos feet) {
		BlockPos below = feet.below(), head = feet.above();
		if (!level.hasChunkAt(feet) || !level.hasChunkAt(below) || !level.hasChunkAt(head)) return false;
		var floor = level.getBlockState(below);
		if (!floor.isFaceSturdy(level, below, net.minecraft.core.Direction.UP) || !floor.getFluidState().isEmpty()) return false;
		var body = level.getBlockState(feet);
		var upper = level.getBlockState(head);
		if (!body.getFluidState().isEmpty() || !upper.getFluidState().isEmpty()
			|| !body.getCollisionShape(level, feet).isEmpty() || !upper.getCollisionShape(level, head).isEmpty()) return false;
		var path = villager.getNavigation().createPath(feet, 0);
		return path != null && path.canReach();
	}

	static boolean isVillageTavernsLoaded() {
		return FabricLoader.getInstance().isModLoaded(MOD_ID) && BuiltInRegistries.BLOCK.containsKey(TAVERN_BARREL);
	}

	static boolean isVillageTavernTemplate(Identifier template) {
		return TAVERN_TEMPLATES.contains(template);
	}

	static boolean isTavernMarker(BlockState state) {
		return TAVERN_BARREL.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
	}
}
