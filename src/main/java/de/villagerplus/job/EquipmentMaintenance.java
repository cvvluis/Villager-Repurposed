package de.villagerplus.job;

import de.villagerplus.build.Blueprints;
import de.villagerplus.village.Balance;
import de.villagerplus.village.Specials;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Repairs one damaged depot item at an actual anvil in the village toolshed. */
public final class EquipmentMaintenance {
	private static final long ANVIL_SCAN_INTERVAL = 200L;
	private static final long NO_WORK_INTERVAL = 300L;

	private EquipmentMaintenance() {
	}

	public static boolean repairAtToolshed(Ctx c) {
		if (c.now() < c.st().toolRepairNextWorkAt || c.village() == null || !Specials.has(c.village(), Blueprints.TOOLSHED)) {
			return false;
		}
		List<Container> toolsheds = Specials.containers(c.level(), c.village(), Blueprints.TOOLSHED);
		if (toolsheds.isEmpty()) {
			return false;
		}

		BlockPos anvil = findAnvil(c);
		if (anvil == null) {
			return false;
		}
		List<Container> supplies = Balance.storage(c.level(), c.village());
		RepairTarget target = findTarget(toolsheds, supplies);
		if (target == null) {
			c.st().toolRepairNextWorkAt = c.now() + NO_WORK_INTERVAL;
			return false;
		}

		c.setActivity("repariert Ausrüstung an der Werkzeugkammer");
		Anim.hold(c, net.minecraft.world.item.Items.IRON_INGOT);
		if (!JobUtil.approach(c, anvil, 2.8)) {
			return true;
		}
		// The cached target can become stale while the smith is walking. Never spend a repair
		// ingredient unless the worker has reached a still-loaded, still-usable anvil.
		if (!c.level().hasChunkAt(anvil) || !isAnvil(c.level().getBlockState(anvil))) {
			c.st().toolRepairAnvil = null;
			c.st().toolRepairNextWorkAt = c.now() + ANVIL_SCAN_INTERVAL;
			c.st().clearTarget();
			c.st().cooldownUntil = c.now() + 40;
			return true;
		}

		// Rebuild both inventories after walking. In particular, a formerly public toolshed
		// container may have become private while this smith was en route; never mutate the
		// stale Container reference selected before the walk.
		List<Container> currentToolsheds = Specials.containers(c.level(), c.village(), Blueprints.TOOLSHED);
		List<Container> currentSupplies = Balance.storage(c.level(), c.village());
		RepairTarget currentTarget = findTarget(currentToolsheds, currentSupplies);
		if (currentTarget == null) {
			c.st().clearTarget();
			c.st().toolRepairNextWorkAt = c.now() + NO_WORK_INTERVAL;
			c.st().cooldownUntil = c.now() + 40;
			return true;
		}
		ItemStack damaged = currentTarget.depot().getItem(currentTarget.slot());
		RepairMaterial material = findRepairMaterial(damaged, currentSupplies);
		if (damaged.isEmpty() || !damaged.isDamaged() || material == null) {
			c.st().clearTarget();
			c.st().toolRepairNextWorkAt = c.now() + NO_WORK_INTERVAL;
			c.st().cooldownUntil = c.now() + 40;
			return true;
		}

		int restored = Math.max(1, damaged.getMaxDamage() / 4);
		int before = damaged.getDamageValue();
		if (consumeOne(material)) {
			damaged.setDamageValue(Math.max(0, before - restored));
			currentTarget.depot().setChanged();
			c.level().playSound(null, anvil, SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 0.8F, 1.0F);
			c.level().sendParticles(ParticleTypes.CRIT, anvil.getX() + 0.5, anvil.getY() + 1.0, anvil.getZ() + 0.5, 5, 0.25, 0.15, 0.25, 0.02);
			JobUtil.swing(c.v());
			c.mem().jobsDone++;
		}
		c.st().clearTarget();
		c.st().cooldownUntil = c.now() + 160;
		return true;
	}

	private static BlockPos findAnvil(Ctx c) {
		JobState state = c.st();
		BlockPos cached = state.toolRepairAnvil;
		if (cached != null && c.level().hasChunkAt(cached) && isAnvil(c.level().getBlockState(cached))) {
			return cached;
		}
		state.toolRepairAnvil = null;
		if (c.now() < state.toolRepairCheckAt) {
			return null;
		}
		state.toolRepairCheckAt = c.now() + ANVIL_SCAN_INTERVAL;
		BlockPos origin = Specials.get(c.village(), Blueprints.TOOLSHED);
		if (origin == null || !c.level().hasChunkAt(origin)) {
			return null;
		}
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-9, -2, -9), origin.offset(9, 8, 9))) {
			if (c.level().hasChunkAt(pos) && isAnvil(c.level().getBlockState(pos))) {
				state.toolRepairAnvil = pos.immutable();
				return state.toolRepairAnvil;
			}
		}
		return null;
	}

	private static boolean isAnvil(net.minecraft.world.level.block.state.BlockState state) {
		return state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL) || state.is(Blocks.DAMAGED_ANVIL);
	}

	private static RepairTarget findTarget(List<Container> toolsheds, List<Container> supplies) {
		for (Container depot : toolsheds) {
			for (int slot = 0; slot < depot.getContainerSize(); slot++) {
				ItemStack candidate = depot.getItem(slot);
				if (!candidate.isEmpty() && candidate.isDamageableItem() && candidate.isDamaged()
					&& findRepairMaterial(candidate, supplies) != null) {
					return new RepairTarget(depot, slot);
				}
			}
		}
		return null;
	}

	private static RepairMaterial findRepairMaterial(ItemStack damaged, List<Container> supplies) {
		if (damaged.isEmpty() || !damaged.isDamageableItem() || !damaged.isDamaged()) {
			return null;
		}
		for (Container box : supplies) {
			for (int slot = 0; slot < box.getContainerSize(); slot++) {
				ItemStack candidate = box.getItem(slot);
				// An anvil also accepts another item of the same kind, but spending that item here can
				// destroy valuable equipment stored beside the damaged target. Only consume actual,
				// non-damageable repair ingredients from the village's loaded item rules.
				if (!candidate.isEmpty() && !candidate.isDamageableItem() && damaged.isValidRepairItem(candidate)) {
					return new RepairMaterial(box, slot);
				}
			}
		}
		return null;
	}

	private static boolean consumeOne(RepairMaterial material) {
		if (material.slot() >= material.container().getContainerSize()) return false;
		ItemStack stack = material.container().getItem(material.slot());
		if (stack.isEmpty()) return false;
		material.container().removeItem(material.slot(), 1);
		material.container().setChanged();
		return true;
	}

	private record RepairTarget(Container depot, int slot) {
	}

	private record RepairMaterial(Container container, int slot) {
	}
}
