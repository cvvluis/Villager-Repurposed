package de.villagerplus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.E04Role;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityRenderLayerRegistrationCallback;
import net.minecraft.client.model.npc.VillagerModel;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.VillagerRenderer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityTypes;
import java.util.EnumMap;
import java.util.Map;

/** Adds profession-specific outfit overlays while keeping the active villager model and animation. */
public final class E04RoleRenderer {
	private static final Map<E04Role, Identifier> TEXTURES = createTextures();
	private static boolean registered;

	private E04RoleRenderer() { }

	/** Call once from VillagerPlusClient.onInitializeClient(). */
	public static void register() {
		if (registered) return;
		registered = true;
		LivingEntityRenderLayerRegistrationCallback.EVENT.register((entityType, entityRenderer, registrationHelper, context) -> {
			if (entityType == EntityTypes.VILLAGER && entityRenderer instanceof VillagerRenderer villagerRenderer) {
				registrationHelper.register(new RoleSkinLayer(villagerRenderer));
			}
		});
	}

	private static final class RoleSkinLayer extends RenderLayer<VillagerRenderState, VillagerModel> {
		private RoleSkinLayer(VillagerRenderer renderer) {
			super(renderer);
		}

		@Override
		public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
			VillagerRenderState state, float yRot, float xRot) {
			if (state.villagerData == null) return;
			E04Role role = E04Role.fromProfession(state.villagerData.profession());
			if (role == E04Role.NONE) return;

			Identifier texture = TEXTURES.get(role);
			int overlay = LivingEntityRenderer.getOverlayCoords(state, 0.0F);
			collector.order(1).submitModel(getParentModel(), state, poseStack,
				RenderTypes.entityCutout(texture), lightCoords, overlay, state.outlineColor, null);
		}
	}

	private static Identifier texture(String name) {
		return Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "textures/entity/roles/" + name + ".png");
	}

	private static Map<E04Role, Identifier> createTextures() {
		Map<E04Role, Identifier> textures = new EnumMap<>(E04Role.class);
		for (E04Role role : E04Role.values()) {
			if (role != E04Role.NONE) textures.put(role, texture(role.textureName()));
		}
		return Map.copyOf(textures);
	}
}
