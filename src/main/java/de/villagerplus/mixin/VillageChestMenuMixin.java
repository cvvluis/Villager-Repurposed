package de.villagerplus.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import de.villagerplus.village.StockPolicy;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.spongepowered.asm.mixin.Mixin;

/** Vanilla player clicks at the village center can supply goods for the carrier to sort. */
@Mixin(AbstractContainerMenu.class)
abstract class VillageChestMenuMixin {
	@WrapMethod(method = "clicked")
	private void villagerplus$allowManualIntake(int slot, int button, ContainerInput input, Player player,
			Operation<Void> original) {
		if (player instanceof ServerPlayer && !player.isSpectator()
			&& (Object) this instanceof ChestMenu menu) {
			StockPolicy.withPlayerChestInput(menu.getContainer(), () -> original.call(slot, button, input, player));
		} else {
			original.call(slot, button, input, player);
		}
	}
}
