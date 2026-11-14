package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.VillagerMemory;
import de.villagerplus.VillagerPlus;
import de.villagerplus.village.Expedition;
import de.villagerplus.village.Guests;
import de.villagerplus.village.Wellbeing;
import java.util.Comparator;
import java.util.function.Predicate;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** Field care: uses real carried/public medicine or food to treat injured and severely hungry residents. */
public class HealerJob implements Job {
	/** Healers feed healthy residents whose meal satiety or rest-derived energy drops below these values. */
	public static final int LOW_SATIETY_THRESHOLD = 25;
	public static final int LOW_ENERGY_THRESHOLD = 25;

	@Override
	public boolean primary(Ctx c) {
		if (holdingHealing(c) && treat(c)) return true;
		if (hasFoodCareRecipient(c) && hasFood(c) && treat(c)) return true;
		return HealerBrewing.work(c) || treat(c);
	}

	private static boolean holdingHealing(Ctx c) {
		var inventory = c.v().getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) if (healingPotion(inventory.getItem(i))) return true;
		return false;
	}

	@Override
	public boolean indoorsDuringStorm() { return true; }

	public static boolean isHealingPotion(ItemStack stack) { return healingPotion(stack); }

	@Override
	public boolean secondary(Ctx c) { return treat(c); }

	private static boolean treat(Ctx c) {
		if (c.idle(0) || c.village() == null) return false;
		Villager self = c.v();
		LivingEntity patient = c.st().targetEntity instanceof LivingEntity living && careResident(c, living) ? living : null;
		if (patient == null) {
			c.st().clearTarget();
			var bounds = new net.minecraft.world.phys.AABB(c.village().bell()).inflate(Config.VILLAGE_RADIUS);
			var residents = c.level().getEntitiesOfClass(LivingEntity.class, bounds,
				e -> e != self && careResident(c, e));
			// Keep the established emergency-care priority: injured residents are seen before hungry ones.
			patient = residents.stream().filter(e -> hurtResident(c, e))
				.min(Comparator.comparingDouble(e -> e.distanceToSqr(self))).orElseGet(() -> residents.stream()
					.min(Comparator.comparingDouble(e -> e.distanceToSqr(self))).orElse(null));
			if (patient == null) { c.setIdle(0, 300); return false; }
			c.st().targetEntity = patient;
		}

		boolean foodCare = patient instanceof Villager resident && healthyFoodCareRecipient(c, resident);
		boolean exhaustionCare = foodCare && patient instanceof Villager resident && exhausted(resident, c);
		Predicate<ItemStack> treatmentItem = foodCare ? Meals::isFood : HealerJob::isTreatmentItem;
		ItemStack medicine = findMedicine(self.getInventory(), treatmentItem);
		if (medicine.isEmpty()) {
			Container publicChest = JobUtil.box(c);
			ItemStack stock = findMedicine(publicChest, treatmentItem);
			if (publicChest == null || stock.isEmpty()) { c.st().clearTarget(); c.setIdle(0, 300); return false; }
			var chestPos = JobUtil.chest(c);
			if (chestPos == null) { c.st().clearTarget(); c.setIdle(0, 300); return false; }
			// Never remove village stock when the worker cannot carry it safely.
			if (!self.getInventory().canAddItem(stock)) { c.st().clearTarget(); c.setIdle(0, 300); return false; }
			c.setActivity(foodCare ? "holt Essen aus der Dorfkiste" : "holt Arznei aus der Dorfkiste");
			if (!JobUtil.approach(c, chestPos, 3.0)) return true;
			ItemStack picked = takeOne(publicChest, stock);
			if (picked.isEmpty()) { c.st().clearTarget(); return false; }
			ItemStack remainder = self.getInventory().addItem(picked);
			if (!remainder.isEmpty()) {
				// The capacity check above and this commit run on the server thread. Restore stock defensively
				// if a container implementation nevertheless rejects the item after removal.
				net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(null, publicChest, remainder, null);
				c.st().clearTarget();
				return false;
			}
			return true;
		}

		c.setActivity(foodCare ? "füttert einen hungrigen oder erschöpften Dorfbewohner" : "versorgt einen verletzten Dorfbewohner");
		Anim.hold(c, medicine.getItem());
		if (!JobUtil.approach(c, patient.position(), 3.0)) return true;
		ItemStack consumed = takeOne(self.getInventory(), medicine);
		if (consumed.isEmpty()) { c.st().clearTarget(); return false; }
		PotionContents potion = consumed.get(DataComponents.POTION_CONTENTS);
		if (foodCare) {
			var food = consumed.get(DataComponents.FOOD);
			int nutrition = food == null ? 2 : Math.max(1, food.nutrition());
			patient.heal(nutrition);
			if (patient instanceof Villager resident) {
				recordMeal(c, resident, consumed);
				if (exhaustionCare) resident.getAttachedOrCreate(VillagerPlus.MEMORY).lastSleep = c.now();
			}
		} else if (potion != null && healingPotion(potion)) {
			applyPotion(c, patient, potion);
		} else {
			var food = consumed.get(DataComponents.FOOD);
			int nutrition = food == null ? 2 : Math.max(1, food.nutrition());
			patient.heal(nutrition);
			if (patient instanceof Villager resident) recordMeal(c, resident, consumed);
		}
		giveUseRemainder(c, consumed);
		c.level().sendParticles(ParticleTypes.HEART, patient.getX(), patient.getY() + 1.2, patient.getZ(), 4, 0.3, 0.4, 0.3, 0.0);
		JobUtil.swing(self);
		c.mem().jobsDone++;
		c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 300;
		return true;
	}

	private static boolean careResident(Ctx c, LivingEntity e) {
		return hurtResident(c, e) || e instanceof Villager villager && healthyFoodCareRecipient(c, villager);
	}

	private static boolean hurtResident(Ctx c, LivingEntity e) {
		if (!eligibleResident(c, e) || e.getHealth() >= e.getMaxHealth()) return false;
		if (e instanceof Villager v) return !Guests.isGuest(v) && !Expedition.isAway(v);
		return e instanceof IronGolem;
	}

	private static boolean healthyFoodCareRecipient(Ctx c, Villager resident) {
		if (!eligibleResident(c, resident) || resident.getHealth() < resident.getMaxHealth()
			|| Guests.isGuest(resident) || Expedition.isAway(resident)) return false;
		VillagerMemory memory = resident.getAttachedOrCreate(VillagerPlus.MEMORY);
		// Unset/future clocks are neutral defaults, not proof of hunger or exhaustion.
		long now = c.level().getGameTime();
		Wellbeing.Status wellbeing = Wellbeing.of(c.level(), resident, memory);
		boolean hungry = memory.lastMeal != 0 && memory.lastMeal <= now
			&& wellbeing.satiety() < LOW_SATIETY_THRESHOLD;
		boolean tired = memory.lastSleep != 0 && memory.lastSleep <= now
			&& wellbeing.energy() < LOW_ENERGY_THRESHOLD;
		return hungry || tired;
	}

	private static boolean exhausted(Villager resident, Ctx c) {
		VillagerMemory memory = resident.getAttachedOrCreate(VillagerPlus.MEMORY);
		long now = c.level().getGameTime();
		return memory.lastSleep != 0 && memory.lastSleep <= now
			&& Wellbeing.of(c.level(), resident, memory).energy() < LOW_ENERGY_THRESHOLD;
	}

	private static boolean eligibleResident(Ctx c, LivingEntity e) {
		if (!e.isAlive() || c.village() == null) return false;
		var bell = c.village().bell();
		return e.distanceToSqr(bell.getX(), bell.getY(), bell.getZ())
			<= (double) Config.VILLAGE_RADIUS * Config.VILLAGE_RADIUS * 3.0;
	}

	private static boolean hasFoodCareRecipient(Ctx c) {
		if (c.village() == null) return false;
		var bounds = new net.minecraft.world.phys.AABB(c.village().bell()).inflate(Config.VILLAGE_RADIUS);
		return c.level().getEntitiesOfClass(Villager.class, bounds,
			v -> v != c.v() && healthyFoodCareRecipient(c, v)).size() > 0;
	}

	private static boolean hasFood(Ctx c) {
		return !findMedicine(c.v().getInventory(), Meals::isFood).isEmpty()
			|| !findMedicine(JobUtil.box(c), Meals::isFood).isEmpty();
	}

	private static ItemStack findMedicine(Container container, Predicate<ItemStack> filter) {
		if (container == null) return ItemStack.EMPTY;
		for (int i = 0; i < container.getContainerSize(); i++) {
			ItemStack stack = container.getItem(i);
			if (!stack.isEmpty() && filter.test(stack)) return stack.copyWithCount(1);
		}
		return ItemStack.EMPTY;
	}

	private static boolean isTreatmentItem(ItemStack stack) {
		return healingPotion(stack) || Meals.isFood(stack);
	}

	private static void recordMeal(Ctx c, Villager resident, ItemStack food) {
		resident.getAttachedOrCreate(VillagerPlus.MEMORY).lastMeal = c.now();
		if (Meals.isPreparedFood(food)) resident.setAttached(VillagerPlus.PREPARED_MEAL, c.now());
	}

	private static void applyPotion(Ctx c, LivingEntity patient, PotionContents potion) {
		for (MobEffectInstance effect : potion.getAllEffects()) {
			var mob = effect.getEffect().value();
			if (mob.isInstantaneous()) {
				mob.applyInstantaneousEffect(c.level(), c.v(), c.v(), patient, effect.getAmplifier(), 1.0);
			} else {
				patient.addEffect(new MobEffectInstance(effect));
			}
		}
	}

	private static boolean healingPotion(ItemStack stack) {
		if (!stack.is(Items.POTION) && !stack.is(Items.SPLASH_POTION)) return false;
		PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
		return contents != null && healingPotion(contents);
	}

	private static boolean healingPotion(PotionContents contents) {
		boolean healing = false;
		for (MobEffectInstance effect : contents.getAllEffects()) {
			String id = effect.getEffect().value().getDescriptionId();
			if (id.endsWith(".regeneration") || id.endsWith(".instant_health")) healing = true;
			else if (!effect.getEffect().value().isBeneficial()) return false;
		}
		return healing;
	}

	private static ItemStack takeOne(Container source, ItemStack sample) {
		Predicate<ItemStack> same = s -> ItemStack.isSameItemSameComponents(s, sample);
		for (int i = 0; i < source.getContainerSize(); i++) {
			ItemStack stack = source.getItem(i);
			if (!stack.isEmpty() && same.test(stack)) return source.removeItem(i, 1);
		}
		return ItemStack.EMPTY;
	}

	private static void giveUseRemainder(Ctx c, ItemStack consumed) {
		var remainder = consumed.get(DataComponents.USE_REMAINDER);
		var craftingRemainder = consumed.getItem().getCraftingRemainder();
		ItemStack empty = remainder != null ? remainder.convertInto().create()
			: craftingRemainder == null ? ItemStack.EMPTY : craftingRemainder.create();
		if (!empty.isEmpty()) JobUtil.give(c.level(), c.v(), empty);
	}
}
