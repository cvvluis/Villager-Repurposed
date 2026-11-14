package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.VillageRef;
import de.villagerplus.village.ZonePolicy;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

/** Metzger: Vormittags Tierbestaende klein halten und Fleisch sammeln. Nachmittags Rohes in der Dorfkiste garen, Rohfleisch aus dem Kornspeicher im Raeucherofen. */
public class ButcherJob implements Job {
	private static final Set<EntityType<?>> LIVESTOCK = Set.of(EntityTypes.COW, EntityTypes.PIG, EntityTypes.SHEEP, EntityTypes.CHICKEN);

	/** A named, leashed, courting, juvenile, or dead animal is never a culling candidate. */
	public static boolean cullable(Animal animal) {
		return animal != null && animal.isAlive() && !animal.isBaby() && LIVESTOCK.contains(animal.getType())
			&& !animal.hasCustomName() && !animal.isLeashed() && !animal.isInLove();
	}

	private static final List<Craft.Recipe> RECIPES = List.of(
		cook(Items.BEEF, Items.COOKED_BEEF), cook(Items.PORKCHOP, Items.COOKED_PORKCHOP), cook(Items.CHICKEN, Items.COOKED_CHICKEN),
		cook(Items.MUTTON, Items.COOKED_MUTTON), cook(Items.RABBIT, Items.COOKED_RABBIT), cook(Items.COD, Items.COOKED_COD),
		cook(Items.SALMON, Items.COOKED_SALMON), cook(Items.POTATO, Items.BAKED_POTATO));

	private static Craft.Recipe cook(Item raw, Item cooked) {
		return Craft.manualRecipe("Gegartes", () -> new ItemStack(cooked), Craft.of(raw, 1));
	}

	@Override
	public Predicate<ItemStack> produce() {
		return s -> !Villager.FOOD_POINTS.containsKey(s.getItem());
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			return false;
		}
		JobUtil.collectDrops(c, 5.0, 100);
		if (JobUtil.depositSurplus(c, produce(), 8, 0)) {
			return true;
		}
		if (PenExpansion.work(c)) {
			return true;
		}
		JobState st = c.st();
		ServerLevel level = c.level();
		Animal victim = st.targetEntity instanceof Animal a && isSurplusCandidate(c, a) ? a : null;
		if (victim == null) {
			st.clearTarget();
			victim = pickVictim(c);
			if (victim == null) {
				c.setIdle(0, 400);
				return false;
			}
			st.targetEntity = victim;
		}
		c.setActivity("schlachtet Überschuss");
		Anim.hold(c, Items.IRON_SWORD);
		if (!JobUtil.approach(c, victim.position(), 3.4)) {
			return true;
		}
		victim.hurtServer(level, level.damageSources().mobAttack(c.v()), 20.0F);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		st.clearTarget();
		st.cooldownUntil = c.now() + 60;
		return true;
	}

	@Override
	public boolean secondary(Ctx c) {
		if (c.idle(1)) {
			return false;
		}
		// Raw meat in the village chest keeps the existing path. Slaughter loot is delivered to the granary (food belongs
		// there, the central material chest refuses it), so afterwards it is smoked in the marked kitchen smoker with
		// public fuel and returned to the food store.
		if (Craft.run(c, RECIPES) || SmithSmelting.cookMeat(c) || Livestock.tick(c)) {
			return true;
		}
		c.setIdle(1, 300);
		return false;
	}

	static Animal pickVictim(Ctx c) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.pen.isEmpty()) return null;
		AABB penArea = penArea(c, ref);
		return pickVictim(c, c.level().getEntitiesOfClass(Animal.class, penArea));
	}

	/** Select from an already-collected herd while applying the production pen, pasture, cap, and protection rules. */
	static Animal pickVictim(Ctx c, List<Animal> currentHerd) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.pen.isEmpty()) return null;
		AABB penArea = penArea(c, ref);
		AABB reach = c.v().getBoundingBox().inflate(Config.CULL_RADIUS);
		List<ZonePolicy.Zone> pastures = validPastures(ref);
		List<Animal> herd = managedHerd(c, currentHerd, penArea, pastures);
		int capacity = PenExpansion.capacity(c.level(), ref);
		for (EntityType<?> type : LIVESTOCK) {
			List<Animal> sameKind = herd.stream().filter(a -> a.getType() == type).toList();
			if (sameKind.size() <= capacity) {
				continue;
			}
			var candidate = sameKind.stream()
				.filter(ButcherJob::cullable)
				.filter(a -> a.getBoundingBox().intersects(reach))
				.min(Comparator.comparingDouble(a -> a.distanceToSqr(c.v())));
			if (candidate.isPresent()) {
				return candidate.get();
			}
		}
		return null;
	}

	/** Revalidate a retained target after every approach tick so pen edits or herd changes stop a stale cull. */
	static boolean isSurplusCandidate(Ctx c, Animal animal) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.pen.isEmpty() || !cullable(animal)) return false;
		AABB penArea = penArea(c, ref);
		return isSurplusCandidate(c, animal, c.level().getEntitiesOfClass(Animal.class, penArea));
	}

	/** Revalidates a target against the same supplied-herd rules used by selection. */
	static boolean isSurplusCandidate(Ctx c, Animal animal, List<Animal> currentHerd) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.pen.isEmpty() || !cullable(animal)) return false;
		AABB penArea = penArea(c, ref);
		List<ZonePolicy.Zone> pastures = validPastures(ref);
		if (!PenExpansion.contains(c.level(), ref.data().extra.pen.get(), animal)
			|| !Livestock.inPasture(animal, penArea, pastures)) return false;
		long sameSpecies = managedHerd(c, currentHerd, penArea, pastures).stream()
			.filter(a -> a.getType() == animal.getType()).count();
		return sameSpecies > PenExpansion.capacity(c.level(), ref);
	}

	private static List<Animal> managedHerd(Ctx c, List<Animal> animals, AABB penArea, List<ZonePolicy.Zone> pastures) {
		VillageRef ref = c.village();
		if (ref == null || ref.data().extra.pen.isEmpty()) return List.of();
		BlockPos pen = ref.data().extra.pen.get();
		return animals.stream().filter(a -> a != null && a.isAlive() && LIVESTOCK.contains(a.getType())
			&& PenExpansion.contains(c.level(), pen, a) && Livestock.inPasture(a, penArea, pastures)).toList();
	}

	private static AABB penArea(Ctx c, VillageRef ref) {
		return PenExpansion.area(c.level(), ref.data().extra.pen.orElseThrow());
	}

	private static List<ZonePolicy.Zone> validPastures(VillageRef ref) {
		return ref.bellEntity().getAttachedOrElse(VillagerPlus.ZONES, List.of()).stream()
			.filter(z -> "pasture".equals(z.kind()) && ZonePolicy.validBounds(ref, z.kind(), z.minX(), z.minZ(), z.maxX(), z.maxZ()))
			.limit(ZonePolicy.MAX_ZONES).toList();
	}

}
