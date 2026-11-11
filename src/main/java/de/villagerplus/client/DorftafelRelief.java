package de.villagerplus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import de.villagerplus.block.DorftafelNameBlockEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.Direction;

/** Draws the village map of the large board as coloured relief columns on the recessed map panel (one column per map pixel). */
final class DorftafelRelief {
	private static final int W = DorftafelNameBlockEntity.MAP_W;
	private static final int H = DorftafelNameBlockEntity.MAP_H;
	private static final float PX = 1.0F / 16.0F;
	/** Front face of the recessed map panel, in model space (board front is z = 5 px). */
	private static final float BASE_Z = 8.0F * PX - 0.003F;
	private static final float STEP = 0.8F * PX;

	// terrain code -> RGB: meadow, forest, path, water, field, building, sand, stone, snow, clay, plaza, unknown
	private static final int[] COLORS = {0x7FA650, 0x3F7A3A, 0xB89A64, 0x4F86B8, 0xC9B450, 0xA2452F, 0xE2D08A, 0x8D8D8D, 0xF2F5F7, 0xB86F50, 0xD6C79A, 0xE0C995};

	private DorftafelRelief() { }

	static void draw(PoseStack matrices, SubmitNodeCollector queue, int[] relief, Direction facing) {
		if (relief.length != W * H) {
			return;
		}
		matrices.pushPose();
		matrices.translate(0.5, 0.0, 0.5);
		matrices.mulPose(Axis.YP.rotationDegrees(switch (facing) {
			case EAST -> -90.0F;
			case SOUTH -> 180.0F;
			case WEST -> 90.0F;
			default -> 0.0F;
		}));
		matrices.translate(-0.5, 0.0, -0.5);
		queue.submitCustomGeometry(matrices, RenderTypes.debugQuads(), (pose, vc) -> {
			for (int j = 1; j < H - 1; j++) {
				for (int i = 1; i < W - 1; i++) {
					column(pose, vc, relief, i, j);
				}
			}
		});
		matrices.popPose();
	}

	private static int level(int[] relief, int i, int j) {
		return (relief[j * W + i] >> 4) & 7;
	}

	private static void column(PoseStack.Pose pose, VertexConsumer vc, int[] relief, int i, int j) {
		int sample = relief[j * W + i];
		int terrain = Math.min(COLORS.length - 1, sample & 15);
		int mark = (sample >> 8) & 31;
		int h = level(relief, i, j);
		// model space: the viewer's left is +x; sample i = 0 is the leftmost pixel
		float x1 = (16 - i) * PX, x0 = x1 - PX;
		float y1 = (10 - j) * PX, y0 = y1 - PX;
		float z = BASE_Z - h * STEP;
		int top = shade(COLORS[terrain], 0.78F + 0.07F * h);
		if (mark == 1 || mark == 3) {
			top = 0xD84030; // bell and board stand out
			z -= STEP;
		} else if (mark == 9) {
			top = 0xFFF2C0; // villagers
		}
		quad(pose, vc, x0, y0, z, x1, y0, z, x1, y1, z, x0, y1, z, top);
		int side = shade(COLORS[terrain], 0.55F);
		int hl = level(relief, i + 1, j); // neighbour at -x side (right of the viewer)
		int hr = level(relief, i - 1, j);
		int hu = level(relief, i, j - 1);
		int hd = level(relief, i, j + 1);
		float zb = BASE_Z;
		if (hl < h) quad(pose, vc, x0, y0, z, x0, y1, z, x0, y1, zb - hl * STEP, x0, y0, zb - hl * STEP, side);
		if (hr < h) quad(pose, vc, x1, y0, z, x1, y1, z, x1, y1, zb - hr * STEP, x1, y0, zb - hr * STEP, side);
		if (hu < h) quad(pose, vc, x0, y1, z, x1, y1, z, x1, y1, zb - hu * STEP, x0, y1, zb - hu * STEP, side);
		if (hd < h) quad(pose, vc, x0, y0, z, x1, y0, z, x1, y0, zb - hd * STEP, x0, y0, zb - hd * STEP, side);
	}

	private static int shade(int rgb, float f) {
		int r = Math.min(255, (int) (((rgb >> 16) & 255) * f));
		int g = Math.min(255, (int) (((rgb >> 8) & 255) * f));
		int b = Math.min(255, (int) ((rgb & 255) * f));
		return (r << 16) | (g << 8) | b;
	}

	private static void quad(PoseStack.Pose pose, VertexConsumer vc, float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz, int rgb) {
		int argb = 0xFF000000 | rgb;
		vc.addVertex(pose, ax, ay, az).setColor(argb);
		vc.addVertex(pose, bx, by, bz).setColor(argb);
		vc.addVertex(pose, cx, cy, cz).setColor(argb);
		vc.addVertex(pose, dx, dy, dz).setColor(argb);
	}
}
