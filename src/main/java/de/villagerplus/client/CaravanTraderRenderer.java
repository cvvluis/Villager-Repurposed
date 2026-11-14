package de.villagerplus.client;

import de.villagerplus.VillagerPlus;
import de.villagerplus.entity.CaravanTraderEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.resources.Identifier;

/** Independent caravan merchant renderer with its own texture and trader model. */
public final class CaravanTraderRenderer extends MobRenderer<CaravanTraderEntity, ArmedEntityRenderState, CaravanTraderModel> {
	static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(
		VillagerPlus.MOD_ID, "textures/entity/caravan_trader.png");

	public CaravanTraderRenderer(EntityRendererProvider.Context context) {
		super(context, new CaravanTraderModel(context.bakeLayer(CaravanTraderModel.LAYER)), 0.5F);
		// This is the only held-object path: the model positions the trader's one-count cargo copy.
		this.addLayer(new ItemInHandLayer<>(this));
	}

	@Override
	public Identifier getTextureLocation(ArmedEntityRenderState state) {
		return TEXTURE;
	}

	@Override
	public ArmedEntityRenderState createRenderState() { return new ArmedEntityRenderState(); }

	@Override
	public void extractRenderState(CaravanTraderEntity entity, ArmedEntityRenderState state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		ArmedEntityRenderState.extractArmedEntityRenderState(entity, state, this.itemModelResolver, partialTicks);
	}
}
