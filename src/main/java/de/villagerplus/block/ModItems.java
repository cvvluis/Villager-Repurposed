package de.villagerplus.block;

import de.villagerplus.VillagerPlus;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

/** Eigene Gegenstaende der Mod. */
public final class ModItems {
	/** Dorfkarte: traegt Dimension, Glockenposition und Name des Heimatdorfs (als custom_data); verbindet zwei Doerfer. */
	public static final Item DORFKARTE = register("dorfkarte", new Item.Properties().stacksTo(1));
	/** Reusable tool that grants one villager permission to use a chest. */
	public static final Item CHEST_ACCESS = register("chest_access", new Item.Properties().stacksTo(1));

	private ModItems() {
	}

	private static Item register(String name, Item.Properties props) {
		ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, name));
		return Registry.register(BuiltInRegistries.ITEM, key, new Item(props.setId(key)));
	}

	public static void initialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(tab -> {
			tab.accept(DORFKARTE);
			tab.accept(CHEST_ACCESS);
		});
	}
}
