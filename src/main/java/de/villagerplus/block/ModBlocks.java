package de.villagerplus.block;

import de.villagerplus.VillagerPlus;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Eigene Bloecke der Mod. */
public final class ModBlocks {
	public static final Block DORFTAFEL = register("dorftafel", DorftafelBlock::new,
		BlockBehaviour.Properties.of().strength(1.5F).sound(SoundType.WOOD).noOcclusion());
	/** The large four-by-two board with a wood frame; the old board above stays for saved worlds. */
	public static final Block DORFTAFEL_GROSS = register("dorftafel_gross", DorftafelGrossBlock::new,
		BlockBehaviour.Properties.of().strength(1.5F).sound(SoundType.WOOD).noOcclusion());
	public static final BlockEntityType<DorftafelNameBlockEntity> DORFTAFEL_NAME = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE,
		Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "dorftafel_name"),
		FabricBlockEntityTypeBuilder.create(DorftafelNameBlockEntity::new, DORFTAFEL, DORFTAFEL_GROSS).build());

	public static final Block DORFSCHILD = register("dorfschild", DorfschildBlock::new,
		BlockBehaviour.Properties.of().strength(0.8F).sound(SoundType.WOOD).noCollision());

	private ModBlocks() {
	}

	private static Block register(String name, java.util.function.Function<BlockBehaviour.Properties, Block> factory, BlockBehaviour.Properties props) {
		ResourceKey<Block> blockKey = ResourceKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, name));
		Block block = factory.apply(props.setId(blockKey));
		ResourceKey<Item> itemKey = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, name));
		Registry.register(BuiltInRegistries.ITEM, itemKey, new BlockItem(block, new Item.Properties().setId(itemKey).useBlockDescriptionPrefix()));
		return Registry.register(BuiltInRegistries.BLOCK, blockKey, block);
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(tab -> {
			tab.accept(DORFTAFEL_GROSS.asItem());
			tab.accept(DORFSCHILD.asItem());
		});
	}
}
