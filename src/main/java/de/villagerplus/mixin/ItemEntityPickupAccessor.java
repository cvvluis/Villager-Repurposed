package de.villagerplus.mixin;

import java.util.UUID;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ItemEntity.class)
public interface ItemEntityPickupAccessor {
	@Accessor("target")
	UUID villagerplus$pickupTarget();

	@Accessor("target")
	void villagerplus$setPickupTarget(UUID target);
}
