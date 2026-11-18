package de.villagerplus.mixin;

import de.villagerplus.village.BellProtection;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ExplosionDamageCalculator.class)
abstract class VillageBellExplosionMixin {
	@Inject(method = "shouldBlockExplode", at = @At("HEAD"), cancellable = true)
	private void villagerplus$protectVillageBell(Explosion explosion, BlockGetter level, BlockPos pos, BlockState state,
		float power, CallbackInfoReturnable<Boolean> cir) {
		if (explosion.level() instanceof ServerLevel server && BellProtection.protectFromExplosion(server, pos)) {
			cir.setReturnValue(false);
		}
	}
}