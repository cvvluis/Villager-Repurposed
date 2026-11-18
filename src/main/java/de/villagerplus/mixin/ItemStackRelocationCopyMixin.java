package de.villagerplus.mixin;

import de.villagerplus.village.BellProtection;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Kreativ-Kopien einer Umzugs-Glocke erhalten keine zweite Dorfdaten-Kopie. */
@Mixin(ItemStack.class)
public abstract class ItemStackRelocationCopyMixin {
	@Inject(method = "copy", at = @At("RETURN"))
	private void villagerplus$stripRelocationOnCopy(CallbackInfoReturnable<ItemStack> cir) {
		BellProtection.stripRelocationCopy(cir.getReturnValue());
	}
}