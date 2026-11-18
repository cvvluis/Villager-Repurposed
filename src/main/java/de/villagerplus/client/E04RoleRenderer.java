package de.villagerplus.client;

/** Registers client-side role villager rendering (composite textures, no second model pass). */
public final class E04RoleRenderer {
	private static boolean registered;

	private E04RoleRenderer() { }

	/** Call once from VillagerPlusClient.onInitializeClient(). */
	public static void register() {
		if (registered) return;
		registered = true;
		RoleCompositeTextures.bootstrap();
	}
}