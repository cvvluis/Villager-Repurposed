package de.villagerplus.mixin;

import de.villagerplus.client.RoleCompositeTextures;
import de.villagerplus.client.VillagerRenderStateExt;
import de.villagerplus.job.E04Role;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VillagerRenderer.class)
public abstract class VillagerRendererMixin {
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void villagerplus$cacheE04Role(final Villager entity, final VillagerRenderState state, final float partialTicks,
		final CallbackInfo ci) {
		VillagerRenderStateExt ext = (VillagerRenderStateExt) (Object) state;
		if (state.villagerData == null || state.isBaby) {
			ext.villagerplus$setE04Role(E04Role.NONE);
		} else {
			ext.villagerplus$setE04Role(E04Role.fromProfession(state.villagerData.profession()));
		}
	}

	@Inject(method = "getTextureLocation", at = @At("HEAD"), cancellable = true)
	private void villagerplus$roleCompositeTexture(final VillagerRenderState state, final CallbackInfoReturnable<Identifier> cir) {
		VillagerRenderStateExt ext = (VillagerRenderStateExt) (Object) state;
		E04Role role = ext.villagerplus$getE04Role();
		if (role == E04Role.NONE || state.isBaby) return;
		Identifier texture = RoleCompositeTextures.get(role);
		if (texture != null) cir.setReturnValue(texture);
	}
}