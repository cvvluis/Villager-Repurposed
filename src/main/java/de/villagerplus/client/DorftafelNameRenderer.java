package de.villagerplus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import de.villagerplus.block.DorftafelBlock;
import de.villagerplus.block.DorftafelNameBlockEntity;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Renders the current village name across the Dorftafel's wooden header. */
public final class DorftafelNameRenderer implements BlockEntityRenderer<DorftafelNameBlockEntity, DorftafelNameRenderState> {
	// The model's top wooden bar spans y 13.5-16 px of the upper block; the banner title below must stay uncovered.
	private static final float HEADER_CENTER_Y = 14.75F / 16.0F;
	// The large board has a 6 px header bar (y 10-16) and is four blocks wide.
	private static final float WIDE_HEADER_CENTER_Y = 13.0F / 16.0F;
	private static final float WIDE_MAX_WIDTH_BLOCKS = 3.7F;
	private static final float FRONT_Z = 5.0F / 16.0F - 0.004F;
	private static final float MAX_WIDTH_BLOCKS = 2.9F;
	private static final float MAX_TEXT_SCALE = 1.0F / 66.0F;
	private static final int TEXT_COLOR = 0xFFFFE7B5;
	private final Font font;

	public DorftafelNameRenderer(BlockEntityRendererProvider.Context context) {
		this.font = context.font();
	}

	@Override
	public DorftafelNameRenderState createRenderState() {
		return new DorftafelNameRenderState();
	}

	@Override
	public void extractRenderState(DorftafelNameBlockEntity blockEntity, DorftafelNameRenderState state, float tickProgress,
			Vec3 cameraPos, ModelFeatureRenderer.@Nullable CrumblingOverlay crumblingOverlay) {
		BlockEntityRenderer.super.extractRenderState(blockEntity, state, tickProgress, cameraPos, crumblingOverlay);
		state.setVillageName(blockEntity.villageName());
		state.setFacing(blockEntity.getBlockState().getValue(DorftafelBlock.FACING));
		state.setWide(DorftafelBlock.widthOf(blockEntity.getBlockState()) == 4);
		state.setRelief(blockEntity.relief());
	}

	@Override
	public void submit(DorftafelNameRenderState state, PoseStack matrices, SubmitNodeCollector queue, CameraRenderState cameraState) {
		if (state.wide() && state.relief().length > 0) {
			try {
				DorftafelRelief.draw(matrices, queue, state.relief(), state.facing());
			} catch (RuntimeException e) {
				// the map is decoration; never let it break the frame
			}
		}
		String name = state.villageName();
		if (name == null || name.isBlank()) {
			return;
		}

		int width = this.font.width(name);
		float scale = Math.min(MAX_TEXT_SCALE, (state.wide() ? WIDE_MAX_WIDTH_BLOCKS : MAX_WIDTH_BLOCKS) / Math.max(1, width));
		matrices.pushPose();
		matrices.translate(0.5, state.wide() ? WIDE_HEADER_CENTER_Y : HEADER_CENTER_Y, 0.5);
		matrices.mulPose(Axis.YP.rotationDegrees(yRotation(state.facing())));
		if (state.wide()) {
			// The name block is the first middle column: the board centre lies half a block towards the viewer's right.
			matrices.translate(-0.5, 0.0, 0.0);
		}
		matrices.translate(0.0, 0.0, FRONT_Z - 0.5F);
		// Font coordinates grow right and down; the board's front faces north in model space.
		matrices.scale(-scale, -scale, scale);
		queue.submitText(matrices, -width / 2.0F, -4.0F, Component.literal(name).getVisualOrderText(), false,
			Font.DisplayMode.NORMAL, state.lightCoords, TEXT_COLOR, 0, 0);
		matrices.popPose();
	}

	private static float yRotation(Direction facing) {
		return switch (facing) {
			case EAST -> -90.0F;
			case SOUTH -> 180.0F;
			case WEST -> 90.0F;
			default -> 0.0F;
		};
	}
}
