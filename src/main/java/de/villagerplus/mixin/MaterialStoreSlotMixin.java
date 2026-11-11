package de.villagerplus.mixin;

import de.villagerplus.village.StockPolicy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vanilla chest slots default to accepting everything; ask their inventory predicate before insertion. */
@Mixin(Slot.class)
public abstract class MaterialStoreSlotMixin {
	@Inject(method = "mayPlace", at = @At("HEAD"), cancellable = true)
	private void villagerplus$checkMaterialStoreSlot(ItemStack offered, CallbackInfoReturnable<Boolean> cir) {
		if (offered.isEmpty() || StockPolicy.acceptsMaterialStoreItem(offered.getItem())) return;

		Slot slot = (Slot) (Object) this;
		if (villagerplus$rejectsAtMaterialStore(slot.container, offered)) {
			cir.setReturnValue(false);
		}
	}

	/** Vanilla's shift-click merge pass asks for a stack limit without calling mayPlace. */
	@Inject(method = "getMaxStackSize(Lnet/minecraft/world/item/ItemStack;)I", at = @At("HEAD"), cancellable = true)
	private void villagerplus$preventMaterialStoreMerge(ItemStack offered, CallbackInfoReturnable<Integer> cir) {
		if (offered.isEmpty() || StockPolicy.acceptsMaterialStoreItem(offered.getItem())) return;
		Slot slot = (Slot) (Object) this;
		if (villagerplus$rejectsAtMaterialStore(slot.container, offered)) {
			cir.setReturnValue(slot.getItem().getCount());
		}
	}

	@Unique
	private static boolean villagerplus$rejectsAtMaterialStore(Container container, ItemStack offered) {
		if (container instanceof BaseContainerBlockEntity blockEntity
			&& blockEntity.getLevel() instanceof ServerLevel level) {
			return StockPolicy.rejectsDirectMaterialStoreInsert(level, blockEntity.getBlockPos(), offered);
		}
		if (container instanceof CompoundContainerAccessorMixin compound) {
			return villagerplus$rejectsAtMaterialStore(compound.villagerplus$getContainer1(), offered)
				|| villagerplus$rejectsAtMaterialStore(compound.villagerplus$getContainer2(), offered);
		}
		return false;
	}
}
