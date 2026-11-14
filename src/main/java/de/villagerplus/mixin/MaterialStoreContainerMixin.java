package de.villagerplus.mixin;

import de.villagerplus.village.StockPolicy;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Supplies the insertion predicate that vanilla container block entities otherwise inherit as "accept all". */
@Mixin(BaseContainerBlockEntity.class)
public abstract class MaterialStoreContainerMixin implements Container {
	/**
	 * Vanilla's Container default is true. This override only refuses disallowed items when this
	 * block entity occupies a registered, public village material-store chest.
	 */
	@Override
	public boolean canPlaceItem(int slot, ItemStack offered) {
		BaseContainerBlockEntity entity = (BaseContainerBlockEntity) (Object) this;
		if (offered.isEmpty() || !(entity.getLevel() instanceof ServerLevel level)) return true;
		return !StockPolicy.rejectsDirectMaterialStoreInsert(level, entity.getBlockPos(), offered);
	}
}
