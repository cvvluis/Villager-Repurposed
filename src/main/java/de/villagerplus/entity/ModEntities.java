package de.villagerplus.entity;

import de.villagerplus.VillagerPlus;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;

/** Isolated entity identity for our caravan trader; regular wandering traders keep vanilla rendering and behavior. */
public final class ModEntities {
	private static final ResourceKey<EntityType<?>> CARAVAN_TRADER_KEY = ResourceKey.create(
		Registries.ENTITY_TYPE, Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "caravan_trader"));

	public static final EntityType<CaravanTraderEntity> CARAVAN_TRADER = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		CARAVAN_TRADER_KEY,
		EntityType.Builder.of(CaravanTraderEntity::new, MobCategory.CREATURE)
			.sized(0.6F, 1.95F)
			.eyeHeight(1.62F)
			.clientTrackingRange(10)
			.build(CARAVAN_TRADER_KEY));

	private ModEntities() { }

	public static void initialize() {
		FabricDefaultAttributeRegistry.register(CARAVAN_TRADER, Mob.createMobAttributes());
	}
}
