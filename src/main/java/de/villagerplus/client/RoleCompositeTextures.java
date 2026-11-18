package de.villagerplus.client;

import com.mojang.blaze3d.platform.NativeImage;
import de.villagerplus.VillagerPlus;
import de.villagerplus.job.E04Role;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.ARGB;

/** Pre-composited villager base plus role overlay; one texture draw per role villager. */
public final class RoleCompositeTextures implements SimpleSynchronousResourceReloadListener {
	static final Identifier BASE =
		Identifier.withDefaultNamespace("textures/entity/villager/villager.png");
	private static final Map<E04Role, Identifier> COMPOSITE_IDS = createIds();
	private static final Map<E04Role, Identifier> LIVE = new EnumMap<>(E04Role.class);

	private RoleCompositeTextures() { }

	static void bootstrap() {
		net.fabricmc.fabric.api.resource.ResourceManagerHelper.get(net.minecraft.server.packs.PackType.CLIENT_RESOURCES)
			.registerReloadListener(new RoleCompositeTextures());
	}

	public static Identifier get(E04Role role) {
		return LIVE.get(role);
	}

	@Override
	public Identifier getFabricId() {
		return Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "role_composite_textures");
	}

	@Override
	public void onResourceManagerReload(final ResourceManager manager) {
		var textures = Minecraft.getInstance().getTextureManager();
		for (Identifier id : LIVE.values()) textures.release(id);
		LIVE.clear();

		NativeImage base = load(manager, BASE);
		if (base == null) return;
		try {
			for (E04Role role : E04Role.values()) {
				if (role == E04Role.NONE) continue;
				Identifier overlayId = overlayTexture(role);
				NativeImage overlay = load(manager, overlayId);
				if (overlay == null) continue;
				try {
					NativeImage composed = composite(base, overlay);
					Identifier id = COMPOSITE_IDS.get(role);
					textures.register(id, new DynamicTexture(() -> "villagerplus role " + role.name(), composed));
					LIVE.put(role, id);
				} finally {
					overlay.close();
				}
			}
		} finally {
			base.close();
		}
	}

	private static NativeImage composite(final NativeImage base, final NativeImage overlay) {
		int width = Math.min(base.getWidth(), overlay.getWidth());
		int height = Math.min(base.getHeight(), overlay.getHeight());
		NativeImage out = new NativeImage(base.getWidth(), base.getHeight(), false);
		out.copyFrom(base);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				int src = overlay.getPixel(x, y);
				if (ARGB.alpha(src) == 0) continue;
				int dst = out.getPixel(x, y);
				out.setPixel(x, y, ARGB.alphaBlend(dst, src));
			}
		}
		return out;
	}

	private static NativeImage load(final ResourceManager manager, final Identifier id) {
		try {
			Resource resource = manager.getResourceOrThrow(id);
			try (InputStream stream = resource.open()) {
				return NativeImage.read(stream);
			}
		} catch (IOException | RuntimeException ignored) {
			return null;
		}
	}

	private static Identifier overlayTexture(final E04Role role) {
		return Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID,
			"textures/entity/roles/" + role.textureName() + ".png");
	}

	private static Map<E04Role, Identifier> createIds() {
		Map<E04Role, Identifier> ids = new EnumMap<>(E04Role.class);
		for (E04Role role : E04Role.values()) {
			if (role == E04Role.NONE) continue;
			ids.put(role, Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID,
				"textures/entity/role_composite/" + role.textureName()));
		}
		return Map.copyOf(ids);
	}
}