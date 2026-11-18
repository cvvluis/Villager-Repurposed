package de.villagerplus.mixin;

import de.villagerplus.client.VillagerRenderStateExt;
import de.villagerplus.job.E04Role;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(VillagerRenderState.class)
public class VillagerRenderStateMixin implements VillagerRenderStateExt {
	@Unique private E04Role villagerplus$e04Role = E04Role.NONE;

	@Override
	public E04Role villagerplus$getE04Role() {
		return villagerplus$e04Role;
	}

	@Override
	public void villagerplus$setE04Role(final E04Role role) {
		villagerplus$e04Role = role == null ? E04Role.NONE : role;
	}
}