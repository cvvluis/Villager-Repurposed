package de.villagerplus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.villagerplus.client.VillagerRenderStateExt;
import de.villagerplus.job.E04Role;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.VillagerLikeModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.VillagerProfessionLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.entity.state.VillagerDataHolderRenderState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Role villagers use a single composite skin; skip biome type and profession overlay passes. */
@Mixin(VillagerProfessionLayer.class)
public abstract class VillagerProfessionLayerMixin<
	S extends LivingEntityRenderState & VillagerDataHolderRenderState,
	M extends EntityModel<S> & VillagerLikeModel<S>> {
	@Inject(method = "submit", at = @At("HEAD"), cancellable = true)
	private void villagerplus$skipForRoleComposite(final PoseStack poseStack, final SubmitNodeCollector submitNodeCollector,
		final int lightCoords, final S state, final float yRot, final float xRot, final CallbackInfo ci) {
		if (!(state instanceof VillagerRenderState)) return;
		VillagerRenderStateExt ext = (VillagerRenderStateExt) state;
		E04Role role = ext.villagerplus$getE04Role();
		if (role != E04Role.NONE && !state.isBaby) ci.cancel();
	}
}