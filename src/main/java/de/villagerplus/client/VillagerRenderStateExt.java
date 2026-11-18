package de.villagerplus.client;

import de.villagerplus.job.E04Role;

/** Cached E04 role on {@link net.minecraft.client.renderer.entity.state.VillagerRenderState}. */
public interface VillagerRenderStateExt {
	E04Role villagerplus$getE04Role();

	void villagerplus$setE04Role(E04Role role);
}