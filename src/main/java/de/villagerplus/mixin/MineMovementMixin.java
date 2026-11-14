package de.villagerplus.mixin;

import de.villagerplus.job.Mining;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps actual server movement inside a saved mine's single-cell terraced route. */
@Mixin(Entity.class)
public abstract class MineMovementMixin {
	@Inject(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
		at = @At("HEAD"), cancellable = true, require = 1)
	private void villagerplus$confineMineWalker(MoverType type, Vec3 movement, CallbackInfo ci) {
		if ((Object) this instanceof Villager villager && !Mining.allowMineMovement(villager, movement)) {
			villager.setDeltaMovement(Vec3.ZERO);
			ci.cancel();
		}
	}
}
