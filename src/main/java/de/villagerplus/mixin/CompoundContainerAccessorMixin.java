package de.villagerplus.mixin;

import net.minecraft.world.CompoundContainer;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the two Vanilla double-chest halves so player-slot filtering can target registered stores only. */
@Mixin(CompoundContainer.class)
public interface CompoundContainerAccessorMixin {
	@Accessor("container1")
	Container villagerplus$getContainer1();

	@Accessor("container2")
	Container villagerplus$getContainer2();
}
