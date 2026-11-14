package de.villagerplus.job;

import de.villagerplus.Config;
import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Specials;
import java.util.Comparator;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Ruestungs-, Waffen- und Werkzeugschmied: repariert beschaedigte Eisengolems (mit Eisenbarren deutlich besser)
 * und schmilzt in der Freizeit Roheisen zu Barren.
 */
public class SmithJob implements Job {
	private static final List<Craft.Recipe> RECIPES = List.of(
		Craft.recipe("Eisenbarren", () -> new ItemStack(Items.IRON_INGOT), Craft.of(Items.IRON_NUGGET, 9)));

	@Override
	public boolean indoorsDuringStorm() { return true; }

	@Override
	public java.util.function.Predicate<ItemStack> produce() {
		return s -> !Meals.isFood(s);
	}

	@Override
	public boolean primary(Ctx c) {
		if (c.idle(0)) {
			return false;
		}
		JobState st = c.st();
		ServerLevel level = c.level();
		IronGolem golem = st.targetEntity instanceof IronGolem g && g.isAlive() && g.getHealth() < g.getMaxHealth() ? g : null;
		if (golem == null) {
			if (st.targetEntity instanceof IronGolem) st.clearTarget();
			List<IronGolem> hurt = level.getEntitiesOfClass(IronGolem.class, c.v().getBoundingBox().inflate(Config.GOLEM_RADIUS),
				g -> g.isAlive() && g.getHealth() <= g.getMaxHealth() - 5.0F);
			golem = hurt.stream().min(Comparator.comparingDouble(g -> g.getHealth() / g.getMaxHealth())).orElse(null);
			if (golem == null) {
				c.setIdle(0, 200);
				return false;
			}
			st.targetEntity = golem;
		}
		c.setActivity("repariert einen Eisengolem");
		Anim.hold(c, Items.IRON_AXE);
		if (!JobUtil.approach(c, golem.position(), 2.8)) {
			return true;
		}

		float heal;
		long cooldown;
		if (takeIngot(c)) {
			heal = Config.GOLEM_HEAL_WITH_INGOT;
			cooldown = 80;
		} else {
			heal = Config.GOLEM_HEAL_SCRAP;
			cooldown = Config.GOLEM_SCRAP_COOLDOWN;
		}
		golem.heal((float) (heal * (1.0 + 0.1 * Skills.level(c.mem()))));
		level.playSound(null, golem.blockPosition(), SoundEvents.IRON_GOLEM_REPAIR, SoundSource.NEUTRAL, 1.0F, 1.0F);
		level.sendParticles(ParticleTypes.HAPPY_VILLAGER, golem.getX(), golem.getY() + 1.5, golem.getZ(), 6, 0.4, 0.5, 0.4, 0.0);
		JobUtil.swing(c.v());
		c.mem().jobsDone++;
		st.clearTarget();
		st.cooldownUntil = c.now() + cooldown;
		return true;
	}

	@Override
	public boolean secondary(Ctx c) {
		if (c.idle(1)) {
			return false;
		}
		if (primary(c)) {
			return true;
		}
		if (ensureRoleWorkstations(c)) return true;

		if (EquipmentMaintenance.repairAtToolshed(c)) {
			return true;
		}
		if (EquipmentProduction.work(c)) {
			return true;
		}
		if (SmithRailCraft.work(c)) {
			return true;
		}
		if (SmithSmelting.work(c)) {
			return true;
		}
		if (Craft.run(c, RECIPES)) {
			return true;
		}
		if (c.village() != null) {
			if (JobUtil.depositSurplus(c, produce(), 48, 0)) {
				return true;
			}
			if (Mining.tick(c, Mining.Need.METAL)) {
				return true;
			}
		}
		c.setIdle(1, 400);
		return false;
	}

	/** Builds missing workstations through loaded crafting recipes using only the real communal depot. */
	static boolean ensureRoleWorkstations(Ctx c) {
		if (Workstation.ensure(c, Blocks.ANVIL)) return true;
		if (Workstation.ensure(c, Blocks.BLAST_FURNACE)) return true;
		if (Workstation.ensure(c, Blocks.BREWING_STAND)) return true;

		// Delight blocks are useful once the village has a marked kitchen. Resolve registry IDs at runtime so
		// servers without the optional mod neither load its classes nor substitute unrelated vanilla blocks.
		if (c.village() == null || !FabricLoader.getInstance().isModLoaded("farmersdelight")) return false;
		BlockPos kitchen = Specials.get(c.village(), Blueprints.KITCHEN);
		if (kitchen == null || !c.level().hasChunkAt(kitchen)) return false;
		return ensureKitchenStation(c, Blueprints.FARMERS_DELIGHT_COOKING_POT, kitchen)
			|| ensureKitchenStation(c, Blueprints.FARMERS_DELIGHT_CUTTING_BOARD, kitchen);
	}

	private static boolean ensureKitchenStation(Ctx c, String id, BlockPos kitchen) {
		Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(id)).orElse(null);
		return block != null && block.asItem() != Items.AIR && Workstation.ensure(c, block, kitchen);
	}
	/** Eisenbarren aus eigenem Inventar oder aus der Dorfkiste. */
	private static boolean takeIngot(Ctx c) {
		if (JobUtil.take(c.v().getInventory(), s -> s.is(Items.IRON_INGOT), 1) > 0) {
			return true;
		}
		BlockPos chest = JobUtil.chest(c);
		if (chest == null) {
			return false;
		}
		Container box = JobUtil.box(c);
		return box != null && JobUtil.take(box, s -> s.is(Items.IRON_INGOT), 1) > 0;
	}
}
