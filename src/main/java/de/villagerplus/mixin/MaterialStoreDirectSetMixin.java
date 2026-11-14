package de.villagerplus.mixin;

import de.villagerplus.village.StockPolicy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Guards direct vanilla container writes without changing inventory loading or saved contents. */
@Mixin(RandomizableContainerBlockEntity.class)
public abstract class MaterialStoreDirectSetMixin {
	/**
	 * Vanilla loads saved chest inventory lists through loadAdditional/ContainerHelper rather than
	 * this setter. Checking at HEAD also avoids unpacking a loot table for a write that is refused.
	 */
	@Inject(method = "setItem(ILnet/minecraft/world/item/ItemStack;)V", at = @At("HEAD"), cancellable = true)
	private void villagerplus$guardDirectMaterialStoreWrite(int slot, ItemStack offered, CallbackInfo ci) {
		if (offered.isEmpty()) return;
		RandomizableContainerBlockEntity entity = (RandomizableContainerBlockEntity) (Object) this;
		if (slot >= 0 && slot < entity.getContainerSize() && entity.getLevel() instanceof ServerLevel level
			&& StockPolicy.rejectsDirectMaterialStoreInsert(level, entity.getBlockPos(), offered)) {
			ci.cancel();
		}
	}
}
