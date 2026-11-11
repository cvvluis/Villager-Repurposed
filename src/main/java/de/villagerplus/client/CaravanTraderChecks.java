package de.villagerplus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import de.villagerplus.entity.CaravanTraderEntity;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Arrays;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/** Focused, client-side geometry and renderer checks for the caravan trader. */
public final class CaravanTraderChecks {
	private CaravanTraderChecks() { }

	public static int run() throws ReflectiveOperationException {
		ModelPart root = CaravanTraderModel.createBodyLayer().bakeRoot();
		CaravanTraderModel model = new CaravanTraderModel(root);
		ModelPart head = root.getChild("head");
		ModelPart hat = head.getChild("hat");
		ModelPart crown = hat.getChild("hat_crown");
		ModelPart brim = hat.getChild("hat_brim");
		ModelPart band = hat.getChild("hat_band");
		ModelPart nose = head.getChild("nose");
		int checks = 0;
		checks += check(de.villagerplus.entity.CaravanTraderEntity.class.getSuperclass()
				== net.minecraft.world.entity.npc.villager.AbstractVillager.class
			&& !net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class.isAssignableFrom(
				de.villagerplus.entity.CaravanTraderEntity.class),
			"caravan trader remains its own villager entity instead of inheriting wandering-trader visuals");
		Vec3 backRopeAnchor = CaravanTraderEntity.ropeHoldOffset(0.0F, 1.95);
		Vec3 turnedRopeAnchor = CaravanTraderEntity.ropeHoldOffset(90.0F, 1.95);
		double fittedShoulderHeight = (CaravanTraderModel.MODEL_BOTTOM_Y - 2.0F)
			* CaravanTraderModel.ENTITY_FIT_SCALE / 16.0F;
		checks += check(Arrays.stream(CaravanTraderEntity.class.getDeclaredMethods())
				.anyMatch(method -> method.getName().equals("getRopeHoldPosition")
					&& Arrays.equals(method.getParameterTypes(), new Class<?>[] {float.class})
					&& method.getReturnType() == Vec3.class)
			&& close(backRopeAnchor.x, 0.0) && close(backRopeAnchor.y, 1.95 * 0.58) && close(backRopeAnchor.z, -0.45)
			&& Math.abs(backRopeAnchor.y - fittedShoulderHeight) < 0.001
			&& close(turnedRopeAnchor.x, 0.45) && close(turnedRopeAnchor.z, 0.0)
			&& close(turnedRopeAnchor.y, 1.95 * 0.58) && close(turnedRopeAnchor.z, 0.0)
			&& CaravanTraderModel.PACK_Z > 0.0F && CaravanTraderModel.NOSE_BOX_Z < 0.0F,
			"native rope endpoint stays at shoulder height on the model's rear-pack side after renderer rotation and follows body yaw");
		checks += check(CaravanTraderModel.class.getSuperclass() == net.minecraft.client.model.EntityModel.class,
			"trader model is independent from vanilla villager and wandering-trader models");
		checks += check(ArmedModel.class.isAssignableFrom(CaravanTraderModel.class),
			"the independent model keeps vanilla main-hand rendering for physical trade stock");
		checks += check(close(CaravanTraderModel.HEAD_TOP, -10.0F)
			&& close(CaravanTraderModel.HEAD_WIDTH, 8.0F) && close(CaravanTraderModel.HEAD_HEIGHT, 10.0F)
			&& close(CaravanTraderModel.HEAD_DEPTH, 8.0F)
			&& close(CaravanTraderModel.TORSO_WIDTH, 8.0F) && close(CaravanTraderModel.TORSO_HEIGHT, 12.0F)
			&& close(CaravanTraderModel.TORSO_DEPTH, 6.0F)
			&& close(CaravanTraderModel.ARM_WIDTH, 4.0F) && close(CaravanTraderModel.ARM_HEIGHT, 12.0F)
			&& close(CaravanTraderModel.ARM_DEPTH, 4.0F)
			&& close(CaravanTraderModel.LEG_WIDTH, 4.0F) && close(CaravanTraderModel.LEG_HEIGHT, 12.0F)
			&& close(CaravanTraderModel.LEG_DEPTH, 4.0F),
			"head, torso, arms, and legs retain standard adult-villager dimensions");

		checks += check(close(crown.y + CaravanTraderModel.HAT_CROWN_HEIGHT, brim.y)
			&& close(brim.y + CaravanTraderModel.HAT_BRIM_HEIGHT, CaravanTraderModel.HEAD_TOP),
			"hat crown sits on the brim and the brim sits directly on the head");
		checks += check(CaravanTraderModel.HAT_CROWN_WIDTH < 8.0F
			&& CaravanTraderModel.HAT_BRIM_WIDTH > CaravanTraderModel.HAT_CROWN_WIDTH
			&& CaravanTraderModel.HAT_BRIM_WIDTH <= CaravanTraderModel.HEAD_WIDTH
			&& CaravanTraderModel.HAT_BRIM_DEPTH >= CaravanTraderModel.HEAD_DEPTH + 1.0F
			&& CaravanTraderModel.HAT_BRIM_DEPTH <= CaravanTraderModel.HEAD_DEPTH + 1.5F
			&& CaravanTraderModel.HAT_CROWN_HEIGHT >= 3.0F,
			"hat has a distinct crown and a forward-back brim that stays within the head's width");
		int brimUvWidth = (int) (2.0F * (CaravanTraderModel.HAT_BRIM_WIDTH + CaravanTraderModel.HAT_BRIM_TEXTURE_DEPTH));
		int brimUvHeight = (int) Math.ceil(CaravanTraderModel.HAT_BRIM_HEIGHT + CaravanTraderModel.HAT_BRIM_TEXTURE_DEPTH);
		checks += check(uvInside(CaravanTraderModel.HAT_BRIM_U, CaravanTraderModel.HAT_BRIM_V, brimUvWidth, brimUvHeight)
			&& CaravanTraderModel.HAT_BRIM_U == CaravanTraderModel.HAT_CROWN_U
			&& CaravanTraderModel.HAT_BRIM_V == CaravanTraderModel.HAT_CROWN_V
			&& CaravanTraderModel.HAT_BRIM_U + brimUvWidth <= CaravanTraderModel.HAT_CROWN_U
				+ (int) (2.0F * (CaravanTraderModel.HAT_CROWN_WIDTH * 2.0F))
			&& CaravanTraderModel.HAT_BRIM_V + brimUvHeight <= CaravanTraderModel.HAT_CROWN_V
				+ (int) (CaravanTraderModel.HAT_CROWN_HEIGHT + CaravanTraderModel.HAT_CROWN_WIDTH)
			&& close(CaravanTraderModel.HAT_BRIM_TEXTURE_DEPTH + 2.0F * CaravanTraderModel.HAT_BRIM_DEPTH_GROW,
				CaravanTraderModel.HAT_BRIM_DEPTH)
			&& !uvOverlaps(CaravanTraderModel.HAT_BRIM_U, CaravanTraderModel.HAT_BRIM_V, brimUvWidth, brimUvHeight,
				0, 38, 28, (int) (CaravanTraderModel.COAT_LENGTH + CaravanTraderModel.TORSO_DEPTH)),
			"expanded hat brim keeps its full depth while sampling hat fabric instead of the coat atlas");
		checks += check(band.y >= crown.y
			&& close(band.y + CaravanTraderModel.HAT_BAND_HEIGHT, brim.y),
			"hat band wraps the crown base without floating below the brim");
		float noseTopY = nose.y + CaravanTraderModel.NOSE_BOX_Y;
		float noseBottomY = noseTopY + CaravanTraderModel.NOSE_HEIGHT;
		checks += check(close(nose.x, 0.0F) && close(nose.y, CaravanTraderModel.NOSE_PART_Y) && close(nose.z, 0.0F)
			&& close(noseTopY, -4.0F) && close(noseBottomY, -2.0F)
			&& noseTopY >= CaravanTraderModel.HEAD_TOP && noseBottomY <= 0.0F
			&& CaravanTraderModel.NOSE_WIDTH <= 1.0F && CaravanTraderModel.NOSE_DEPTH <= 1.0F
			&& close(CaravanTraderModel.NOSE_BOX_Z + CaravanTraderModel.NOSE_DEPTH, -4.0F)
			&& uvInside(CaravanTraderModel.NOSE_U, CaravanTraderModel.NOSE_V, 4, 3)
			&& CaravanTraderModel.NOSE_U == 0 && CaravanTraderModel.NOSE_V == 0
			&& !uvOverlaps(CaravanTraderModel.NOSE_U, CaravanTraderModel.NOSE_V, 4, 3, 8, 0, 8, 8)
			&& !uvOverlaps(CaravanTraderModel.NOSE_U, CaravanTraderModel.NOSE_V, 4, 3, 8, 8, 8, 10),
			"a narrow human-like nose bridge stays flush to the face and uses a dedicated skin patch without covering eye pixels");

		ModelPart body = root.getChild("body");
		ModelPart pack = root.getChild("pack");
		int coatUvHeight = (int) (CaravanTraderModel.COAT_LENGTH + CaravanTraderModel.TORSO_DEPTH);
		checks += check(close(CaravanTraderModel.COAT_START_Y, 0.0F)
			&& close(CaravanTraderModel.COAT_LENGTH, CaravanTraderModel.TORSO_HEIGHT)
			&& close(CaravanTraderModel.COAT_DEFORMATION, 0.25F)
			&& uvInside(0, 38, 28, coatUvHeight)
			&& body.getChild("field_coat") != null
			&& body.getChild("split_coat_tail_left") != null && body.getChild("split_coat_tail_right") != null
			&& close(CaravanTraderModel.COAT_TAIL_START_Y, CaravanTraderModel.TORSO_HEIGHT - 2.0F)
			&& CaravanTraderModel.COAT_TAIL_START_Y + CaravanTraderModel.COAT_TAIL_LENGTH
				<= CaravanTraderModel.TORSO_HEIGHT + 2.0F
			&& CaravanTraderModel.LEG_HEIGHT - Math.max(0.0F,
				CaravanTraderModel.COAT_TAIL_START_Y + CaravanTraderModel.COAT_TAIL_LENGTH - CaravanTraderModel.TORSO_HEIGHT) >= 10.0F
			&& close(CaravanTraderModel.COAT_TAIL_WIDTH * 2.0F + CaravanTraderModel.COAT_TAIL_SPLIT,
				CaravanTraderModel.TORSO_WIDTH),
			"a short split coat hem keeps the travel-jacket silhouette while leaving most villager legs visible");
		int packUvWidth = (int) (2.0F * (CaravanTraderModel.PACK_WIDTH + CaravanTraderModel.PACK_DEPTH));
		int packUvHeight = (int) (CaravanTraderModel.PACK_HEIGHT + CaravanTraderModel.PACK_DEPTH);
		int strapUvWidth = CaravanTraderModel.STRAP_UV_WIDTH;
		int strapUvHeight = CaravanTraderModel.STRAP_UV_HEIGHT;
		int pouchUvWidth = CaravanTraderModel.POUCH_UV_WIDTH;
		int pouchUvHeight = CaravanTraderModel.POUCH_UV_HEIGHT;
		float leftPouchMinX = CaravanTraderModel.LEFT_SIDE_POUCH_X - CaravanTraderModel.SIDE_POUCH_DEFORMATION;
		float leftPouchMaxX = CaravanTraderModel.LEFT_SIDE_POUCH_X + CaravanTraderModel.SIDE_POUCH_WIDTH
			+ CaravanTraderModel.SIDE_POUCH_DEFORMATION;
		float rightPouchMinX = CaravanTraderModel.RIGHT_SIDE_POUCH_X - CaravanTraderModel.SIDE_POUCH_DEFORMATION;
		float rightPouchMaxX = CaravanTraderModel.RIGHT_SIDE_POUCH_X + CaravanTraderModel.SIDE_POUCH_WIDTH
			+ CaravanTraderModel.SIDE_POUCH_DEFORMATION;
		checks += check(CaravanTraderModel.PACK_WIDTH <= CaravanTraderModel.TORSO_WIDTH
			&& CaravanTraderModel.PACK_Y >= 0.0F
			&& CaravanTraderModel.PACK_Y + CaravanTraderModel.PACK_HEIGHT <= CaravanTraderModel.TORSO_HEIGHT
			&& close(CaravanTraderModel.PACK_Z, CaravanTraderModel.TORSO_DEPTH / 2.0F
				+ CaravanTraderModel.COAT_DEFORMATION + CaravanTraderModel.PACK_COAT_CLEARANCE)
			&& CaravanTraderModel.PACK_COAT_CLEARANCE >= 0.1F
			&& CaravanTraderModel.PACK_Z + CaravanTraderModel.PACK_DEPTH
				> CaravanTraderModel.TORSO_DEPTH / 2.0F + CaravanTraderModel.COAT_DEFORMATION
			&& !hasChild(body, "stock_pack") && pack.getChild("stock_pack") != null
			&& uvInside(CaravanTraderModel.PACK_U, CaravanTraderModel.PACK_V, packUvWidth, packUvHeight)
			&& !uvOverlaps(CaravanTraderModel.PACK_U, CaravanTraderModel.PACK_V, packUvWidth, packUvHeight,
				CaravanTraderModel.STRAP_U, CaravanTraderModel.STRAP_V,
				strapUvWidth, strapUvHeight),
			"frame pack sits behind the coat with no intersecting shell and uses a separate in-bounds texture region");
		Bounds coatBounds = new Bounds(-CaravanTraderModel.TORSO_WIDTH / 2.0F - CaravanTraderModel.COAT_DEFORMATION,
			CaravanTraderModel.TORSO_WIDTH / 2.0F + CaravanTraderModel.COAT_DEFORMATION,
			CaravanTraderModel.COAT_START_Y - CaravanTraderModel.COAT_DEFORMATION,
			CaravanTraderModel.COAT_START_Y + CaravanTraderModel.COAT_LENGTH + CaravanTraderModel.COAT_DEFORMATION,
			-CaravanTraderModel.TORSO_DEPTH / 2.0F - CaravanTraderModel.COAT_DEFORMATION,
			CaravanTraderModel.TORSO_DEPTH / 2.0F + CaravanTraderModel.COAT_DEFORMATION);
		Bounds packBounds = new Bounds(-CaravanTraderModel.PACK_WIDTH / 2.0F, CaravanTraderModel.PACK_WIDTH / 2.0F,
			CaravanTraderModel.PACK_Y, CaravanTraderModel.PACK_Y + CaravanTraderModel.PACK_HEIGHT,
			CaravanTraderModel.PACK_Z, CaravanTraderModel.PACK_Z + CaravanTraderModel.PACK_DEPTH);
		Bounds bedrollBounds = new Bounds(-CaravanTraderModel.BEDROLL_WIDTH / 2.0F - CaravanTraderModel.BEDROLL_DEFORMATION,
			CaravanTraderModel.BEDROLL_WIDTH / 2.0F + CaravanTraderModel.BEDROLL_DEFORMATION,
			CaravanTraderModel.BEDROLL_Y - CaravanTraderModel.BEDROLL_DEFORMATION,
			CaravanTraderModel.BEDROLL_Y + CaravanTraderModel.BEDROLL_HEIGHT + CaravanTraderModel.BEDROLL_DEFORMATION,
			CaravanTraderModel.BEDROLL_Z - CaravanTraderModel.BEDROLL_DEFORMATION,
			CaravanTraderModel.BEDROLL_Z + CaravanTraderModel.BEDROLL_DEPTH + CaravanTraderModel.BEDROLL_DEFORMATION);
		Bounds leftPouchBounds = new Bounds(leftPouchMinX, leftPouchMaxX,
			CaravanTraderModel.SIDE_POUCH_Y - CaravanTraderModel.SIDE_POUCH_DEFORMATION,
			CaravanTraderModel.SIDE_POUCH_Y + CaravanTraderModel.SIDE_POUCH_HEIGHT + CaravanTraderModel.SIDE_POUCH_DEFORMATION,
			CaravanTraderModel.SIDE_POUCH_Z - CaravanTraderModel.SIDE_POUCH_DEFORMATION,
			CaravanTraderModel.SIDE_POUCH_Z + CaravanTraderModel.SIDE_POUCH_DEPTH + CaravanTraderModel.SIDE_POUCH_DEFORMATION);
		Bounds rightPouchBounds = new Bounds(rightPouchMinX, rightPouchMaxX,
			leftPouchBounds.minY(), leftPouchBounds.maxY(), leftPouchBounds.minZ(), leftPouchBounds.maxZ());
		Bounds rightArmBounds = new Bounds(-5.0F - 3.0F - 0.05F, -5.0F - 3.0F + CaravanTraderModel.ARM_WIDTH + 0.05F,
			2.0F - 2.0F - 0.05F, 2.0F - 2.0F + CaravanTraderModel.ARM_HEIGHT + 0.05F,
			-CaravanTraderModel.ARM_DEPTH / 2.0F - 0.05F, CaravanTraderModel.ARM_DEPTH / 2.0F + 0.05F);
		Bounds leftArmBounds = new Bounds(5.0F - 1.0F - 0.05F, 5.0F - 1.0F + CaravanTraderModel.ARM_WIDTH + 0.05F,
			2.0F - 2.0F - 0.05F, 2.0F - 2.0F + CaravanTraderModel.ARM_HEIGHT + 0.05F,
			-CaravanTraderModel.ARM_DEPTH / 2.0F - 0.05F, CaravanTraderModel.ARM_DEPTH / 2.0F + 0.05F);
		Bounds leftTailBounds = new Bounds(-CaravanTraderModel.TORSO_WIDTH / 2.0F - CaravanTraderModel.COAT_TAIL_DEFORMATION,
			-CaravanTraderModel.TORSO_WIDTH / 2.0F + CaravanTraderModel.COAT_TAIL_WIDTH + CaravanTraderModel.COAT_TAIL_DEFORMATION,
			CaravanTraderModel.COAT_TAIL_START_Y - CaravanTraderModel.COAT_TAIL_DEFORMATION,
			CaravanTraderModel.COAT_TAIL_START_Y + CaravanTraderModel.COAT_TAIL_LENGTH + CaravanTraderModel.COAT_TAIL_DEFORMATION,
			-CaravanTraderModel.TORSO_DEPTH / 2.0F - CaravanTraderModel.COAT_DEFORMATION - 0.08F - CaravanTraderModel.COAT_TAIL_DEFORMATION,
			-CaravanTraderModel.TORSO_DEPTH / 2.0F - CaravanTraderModel.COAT_DEFORMATION - 0.08F
				+ CaravanTraderModel.COAT_TAIL_DEPTH + CaravanTraderModel.COAT_TAIL_DEFORMATION);
		Bounds rightTailBounds = new Bounds(-leftTailBounds.maxX(), -leftTailBounds.minX(),
			leftTailBounds.minY(), leftTailBounds.maxY(), leftTailBounds.minZ(), leftTailBounds.maxZ());
		Bounds legBounds = new Bounds(-4.0F, 4.0F, 12.0F, 12.0F + CaravanTraderModel.LEG_HEIGHT, -2.0F, 2.0F);
		float coatSilhouetteHalfWidth = CaravanTraderModel.TORSO_WIDTH / 2.0F + CaravanTraderModel.COAT_DEFORMATION;
		checks += check(leftPouchBounds.minX() >= -coatSilhouetteHalfWidth
			&& rightPouchBounds.maxX() <= coatSilhouetteHalfWidth
			&& bedrollBounds.minX() >= -coatSilhouetteHalfWidth
			&& bedrollBounds.maxX() <= coatSilhouetteHalfWidth,
			"side pouches and the bedroll stay inside the coated shoulder silhouette instead of sticking out sideways");
		checks += check(!coatBounds.overlaps(packBounds) && !bedrollBounds.overlaps(packBounds)
			&& !leftPouchBounds.overlaps(packBounds) && !rightPouchBounds.overlaps(packBounds)
			&& !leftPouchBounds.overlaps(rightArmBounds) && !rightPouchBounds.overlaps(leftArmBounds)
			&& !leftTailBounds.overlaps(rightTailBounds)
			&& !leftTailBounds.overlaps(legBounds) && !rightTailBounds.overlaps(legBounds)
			&& root.getChild("right_leg").getChild("field_boot") != null
			&& root.getChild("left_leg").getChild("field_boot") != null
			&& CaravanTraderModel.LEG_HEIGHT - CaravanTraderModel.BOOT_HEIGHT >= 0.0F
			&& CaravanTraderModel.BOOT_DEFORMATION < 0.2F,
			"the coat, rear pack, bedroll, side pouches, and split tails have bounded, non-intersecting shells");
		checks += check(pack.getChild("bedroll") != null
			&& CaravanTraderModel.BEDROLL_WIDTH > CaravanTraderModel.PACK_WIDTH
			&& close(CaravanTraderModel.BEDROLL_Y + CaravanTraderModel.BEDROLL_HEIGHT
				+ CaravanTraderModel.BEDROLL_DEFORMATION, CaravanTraderModel.PACK_Y)
			&& CaravanTraderModel.BEDROLL_Z >= CaravanTraderModel.PACK_Z
			&& CaravanTraderModel.BEDROLL_Z + CaravanTraderModel.BEDROLL_DEPTH
				+ CaravanTraderModel.BEDROLL_DEFORMATION <= CaravanTraderModel.PACK_Z + CaravanTraderModel.PACK_DEPTH,
			"rolled travel blanket rests on the pack top without penetrating its shell");
		checks += check(uvInside(CaravanTraderModel.STRAP_U, CaravanTraderModel.STRAP_V,
			strapUvWidth, strapUvHeight)
			&& strapUvWidth >= 2.0F * (CaravanTraderModel.STRAP_WIDTH + CaravanTraderModel.STRAP_DEPTH)
			&& strapUvHeight >= CaravanTraderModel.STRAP_HEIGHT + CaravanTraderModel.STRAP_DEPTH
			&& !uvOverlaps(CaravanTraderModel.STRAP_U, CaravanTraderModel.STRAP_V,
				strapUvWidth, strapUvHeight,
				0, 38, 28, (int) CaravanTraderModel.COAT_LENGTH + 6),
			"front pack straps have their own atlas area instead of sampling the coat");
		float strapLeftMinX = CaravanTraderModel.STRAP_LEFT_X;
		float strapLeftMaxX = strapLeftMinX + CaravanTraderModel.STRAP_WIDTH;
		float strapRightMinX = CaravanTraderModel.STRAP_RIGHT_X;
		float strapRightMaxX = strapRightMinX + CaravanTraderModel.STRAP_WIDTH;
		float coatFront = -CaravanTraderModel.TORSO_DEPTH / 2.0F - CaravanTraderModel.COAT_DEFORMATION;
		checks += check(close(strapLeftMinX, -strapRightMaxX)
			&& close(strapLeftMaxX, -strapRightMinX)
			&& strapLeftMinX > -CaravanTraderModel.ARM_WIDTH && strapLeftMaxX < 0.0F
			&& strapRightMinX > 0.0F && strapRightMaxX < CaravanTraderModel.ARM_WIDTH
			&& CaravanTraderModel.STRAP_Y >= 0.0F
			&& CaravanTraderModel.STRAP_Y + CaravanTraderModel.STRAP_HEIGHT <= CaravanTraderModel.TORSO_HEIGHT
			&& CaravanTraderModel.STRAP_Z < coatFront
			&& CaravanTraderModel.STRAP_Z + CaravanTraderModel.STRAP_DEPTH > coatFront,
			"mirrored straps sit on the coat front, stay between the arms, and span the torso height");
		checks += check(pack.getChild("left_side_pouch") != null && pack.getChild("right_side_pouch") != null
			&& close(CaravanTraderModel.LEFT_SIDE_POUCH_X + CaravanTraderModel.SIDE_POUCH_WIDTH / 2.0F,
				-(CaravanTraderModel.RIGHT_SIDE_POUCH_X + CaravanTraderModel.SIDE_POUCH_WIDTH / 2.0F))
			&& close(leftPouchMinX, -rightPouchMaxX) && close(leftPouchMaxX, -rightPouchMinX)
			&& leftPouchMaxX <= -CaravanTraderModel.PACK_WIDTH / 2.0F - CaravanTraderModel.POUCH_GAP + 0.0001F
			&& rightPouchMinX >= CaravanTraderModel.PACK_WIDTH / 2.0F + CaravanTraderModel.POUCH_GAP - 0.0001F
			&& leftPouchMinX >= -CaravanTraderModel.ARM_WIDTH
			&& rightPouchMaxX <= CaravanTraderModel.ARM_WIDTH
			&& CaravanTraderModel.SIDE_POUCH_Z - CaravanTraderModel.SIDE_POUCH_DEFORMATION
				> CaravanTraderModel.ARM_DEPTH / 2.0F + 0.1F
			&& CaravanTraderModel.SIDE_POUCH_Y - CaravanTraderModel.SIDE_POUCH_DEFORMATION >= CaravanTraderModel.PACK_Y
			&& CaravanTraderModel.SIDE_POUCH_Y + CaravanTraderModel.SIDE_POUCH_HEIGHT
				+ CaravanTraderModel.SIDE_POUCH_DEFORMATION <= CaravanTraderModel.PACK_Y + CaravanTraderModel.PACK_HEIGHT
			&& CaravanTraderModel.SIDE_POUCH_Z - CaravanTraderModel.SIDE_POUCH_DEFORMATION >= CaravanTraderModel.PACK_Z
			&& CaravanTraderModel.SIDE_POUCH_Z + CaravanTraderModel.SIDE_POUCH_DEPTH
				+ CaravanTraderModel.SIDE_POUCH_DEFORMATION <= CaravanTraderModel.PACK_Z + CaravanTraderModel.PACK_DEPTH,
			"side pouches are mirrored, separated from the pack shell, and clear the swinging arms in depth");
		checks += check(uvInside(CaravanTraderModel.LEFT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight)
			&& uvInside(CaravanTraderModel.RIGHT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight)
			&& pouchUvWidth >= 2.0F * (CaravanTraderModel.SIDE_POUCH_WIDTH + CaravanTraderModel.SIDE_POUCH_DEPTH)
			&& pouchUvHeight >= CaravanTraderModel.SIDE_POUCH_HEIGHT + CaravanTraderModel.SIDE_POUCH_DEPTH
			&& !uvOverlaps(CaravanTraderModel.LEFT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight,
				CaravanTraderModel.RIGHT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight)
			&& !uvOverlaps(CaravanTraderModel.LEFT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight,
				CaravanTraderModel.PACK_U, CaravanTraderModel.PACK_V, packUvWidth, packUvHeight)
			&& !uvOverlaps(CaravanTraderModel.RIGHT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight,
				CaravanTraderModel.PACK_U, CaravanTraderModel.PACK_V, packUvWidth, packUvHeight)
			&& !uvOverlaps(CaravanTraderModel.LEFT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight,
				CaravanTraderModel.STRAP_U, CaravanTraderModel.STRAP_V, strapUvWidth, strapUvHeight)
			&& !uvOverlaps(CaravanTraderModel.RIGHT_POUCH_U, CaravanTraderModel.POUCH_V, pouchUvWidth, pouchUvHeight,
				CaravanTraderModel.STRAP_U, CaravanTraderModel.STRAP_V, strapUvWidth, strapUvHeight),
			"side pouches have sufficient, separate texture regions that remain within the atlas");
		checks += check(!hasChild(body, "stock_pack")
			&& !hasChild(head, "hat_crown") && hat.getChild("hat_crown") != null
			&& pack.getChild("stock_pack") != null
			&& pack.getChild("left_back_strap") != null
			&& pack.getChild("right_back_strap") != null
			&& body.getChild("left_pack_strap") != null
			&& body.getChild("right_pack_strap") != null
			&& pack.getChild("left_side_pouch") != null
			&& pack.getChild("right_side_pouch") != null,
			"body, head, hat and pack are separate geometry groups, with the held item rendered by its own layer");

		ModelPart rightArm = root.getChild("right_arm");
		ModelPart leftArm = root.getChild("left_arm");
		ModelPart rightLeg = root.getChild("right_leg");
		ModelPart leftLeg = root.getChild("left_leg");
		checks += check(close(rightArm.x, -5.0F) && close(leftArm.x, 5.0F)
			&& close(rightArm.y, 2.0F) && close(leftArm.y, 2.0F),
			"arms attach symmetrically at the shoulders");

		ArmedEntityRenderState state = new ArmedEntityRenderState();
		state.xRot = 18.0F;
		state.yRot = -32.0F;
		state.walkAnimationPos = 0.5F;
		state.walkAnimationSpeed = 1.0F;
		state.rightHandItemStack = new ItemStack(Items.EMERALD);
		root.y = 0.0F;
		root.xScale = 1.0F;
		root.yScale = 1.0F;
		root.zScale = 1.0F;
		model.setupAnim(state);
		float stride = state.walkAnimationPos * 0.6662F;
		float fittedModelHeight = (CaravanTraderModel.MODEL_BOTTOM_Y - CaravanTraderModel.MODEL_TOP_Y)
			* CaravanTraderModel.ENTITY_FIT_SCALE / 16.0F;
		checks += check(close(root.y, CaravanTraderModel.ENTITY_FIT_Y_OFFSET)
			&& close(root.xScale, CaravanTraderModel.ENTITY_FIT_SCALE)
			&& close(root.yScale, CaravanTraderModel.ENTITY_FIT_SCALE)
			&& close(root.zScale, CaravanTraderModel.ENTITY_FIT_SCALE)
			&& close(fittedModelHeight, CaravanTraderModel.ENTITY_HEIGHT),
			"the model reapplies its uniform, villager-sized body fit for every render state");
		checks += check(close(head.xRot, (float) Math.toRadians(state.xRot))
			&& close(head.yRot, (float) Math.toRadians(state.yRot)),
			"head follows the entity's extracted look angles");
		checks += check(close(rightArm.xRot, CaravanTraderModel.HELD_ITEM_ARM_ANGLE)
			&& close(rightArm.zRot, -CaravanTraderModel.HELD_ITEM_ARM_INWARD_ROLL)
			&& close(leftArm.zRot, 0.0F),
			"displayed stock brings the holding hand forward and slightly inward while the free hand stays relaxed");
		PoseStack heldItemPose = new PoseStack();
		model.translateToHand(state, HumanoidArm.RIGHT, heldItemPose);
		Vector3f heldItemScale = heldItemPose.last().pose().getScale(new Vector3f());
		Vector3f heldShoulder = heldItemPose.last().pose().getTranslation(new Vector3f());
		checks += check(close(heldItemScale.x, CaravanTraderModel.ENTITY_FIT_SCALE)
			&& close(heldItemScale.y, CaravanTraderModel.ENTITY_FIT_SCALE)
			&& close(heldItemScale.z, CaravanTraderModel.ENTITY_FIT_SCALE),
			"held stock inherits the same uniform fit as the merchant instead of growing relative to its body");
		PoseStack heldItemLayerPose = new PoseStack();
		model.translateToHand(state, HumanoidArm.RIGHT, heldItemLayerPose);
		heldItemLayerPose.mulPose(com.mojang.math.Axis.XP.rotationDegrees(-90.0F));
		heldItemLayerPose.mulPose(com.mojang.math.Axis.YP.rotationDegrees(180.0F));
		heldItemLayerPose.translate(1.0F / 16.0F, 2.0F / 16.0F, -10.0F / 16.0F);
		Vector3f heldItemAnchor = heldItemLayerPose.last().pose().getTranslation(new Vector3f());
		checks += check(heldItemAnchor.z < heldShoulder.z - 0.1F
			&& close(heldItemLayerPose.last().pose().getScale(new Vector3f()).x, CaravanTraderModel.ENTITY_FIT_SCALE),
			"the vanilla third-person hand offset places trade stock forward of the shoulder without changing its fitted scale");
		checks += check(close(leftArm.xRot, (float) Math.cos(stride) * CaravanTraderModel.ARM_WALK_SWING)
			&& close(rightLeg.xRot, (float) Math.cos(stride) * CaravanTraderModel.LEG_WALK_SWING)
			&& close(leftLeg.xRot, (float) Math.cos(stride + Math.PI) * CaravanTraderModel.LEG_WALK_SWING),
			"free arm and legs keep opposite walking swings");

		state.rightHandItemStack = ItemStack.EMPTY;
		model.setupAnim(state);
		checks += check(close(rightArm.xRot,
			(float) Math.cos(stride + Math.PI) * CaravanTraderModel.ARM_WALK_SWING)
			&& close(rightArm.zRot, 0.0F),
			"empty hand returns to the walk pose after carrying stock");

		state.leftHandItemStack = new ItemStack(Items.CHEST);
		model.setupAnim(state);
		checks += check(close(leftArm.xRot, CaravanTraderModel.HELD_ITEM_ARM_ANGLE)
			&& close(leftArm.zRot, CaravanTraderModel.HELD_ITEM_ARM_INWARD_ROLL)
			&& close(rightArm.xRot,
				(float) Math.cos(stride + Math.PI) * CaravanTraderModel.ARM_WALK_SWING),
			"left-hand trade goods pose only the matching arm and leave the free arm walking");
		state.leftHandItemStack = ItemStack.EMPTY;

		checks += check(CaravanTraderRenderer.class.getSuperclass() == MobRenderer.class,
			"trader keeps the standard mob nameplate renderer path");
		checks += check(Arrays.stream(CaravanTraderRenderer.class.getDeclaredMethods())
			.anyMatch(method -> method.getName().equals("createRenderState")
				&& method.getReturnType() == ArmedEntityRenderState.class),
			"renderer creates the armed model state needed by the item layer");
		checks += check(Arrays.stream(CaravanTraderRenderer.class.getDeclaredMethods())
			.anyMatch(method -> method.getName().equals("extractRenderState")
				&& Arrays.equals(method.getParameterTypes(), new Class<?>[] {
					de.villagerplus.entity.CaravanTraderEntity.class, ArmedEntityRenderState.class, float.class
				})),
			"renderer extracts entity data into its item-capable render state");
		checks += check(CaravanTraderRenderer.TEXTURE.toString().equals("villagerplus:textures/entity/caravan_trader.png"),
			"renderer uses the dedicated trader texture");
		checks += check(hasExpectedTraderTextureAtlas(),
			"the dedicated trader texture resource is a 64x64 PNG matching the model atlas");
		checks += check(Arrays.stream(CaravanTraderRenderer.class.getDeclaredMethods())
			.noneMatch(method -> method.getName().equals("getNameTag") || method.getName().equals("renderNameTag")),
			"renderer does not replace vanilla custom-name placement");
		return checks;
	}

	private record Bounds(float minX, float maxX, float minY, float maxY, float minZ, float maxZ) {
		private boolean overlaps(Bounds other) {
			return minX < other.maxX && maxX > other.minX
				&& minY < other.maxY && maxY > other.minY
				&& minZ < other.maxZ && maxZ > other.minZ;
		}
	}

	private static int check(boolean condition, String message) {
		if (!condition) throw new IllegalStateException("Caravan trader check failed: " + message);
		return 1;
	}

	private static boolean close(float actual, float expected) {
		return Math.abs(actual - expected) <= 0.0001F;
	}

	private static boolean close(double actual, double expected) {
		return Math.abs(actual - expected) <= 0.0001;
	}

	private static boolean hasChild(ModelPart parent, String name) {
		try {
			parent.getChild(name);
			return true;
		} catch (RuntimeException missing) {
			return false;
		}
	}

	private static boolean uvInside(int u, int v, int width, int height) {
		return u >= 0 && v >= 0 && width > 0 && height > 0 && u + width <= 64 && v + height <= 64;
	}

	private static boolean uvOverlaps(int u1, int v1, int width1, int height1,
		int u2, int v2, int width2, int height2) {
		return u1 < u2 + width2 && u1 + width1 > u2
			&& v1 < v2 + height2 && v1 + height1 > v2;
	}

	private static boolean hasExpectedTraderTextureAtlas() {
		var resource = CaravanTraderChecks.class.getResourceAsStream("/assets/villagerplus/textures/entity/caravan_trader.png");
		if (resource == null) return false;
		try (var input = new DataInputStream(resource)) {
			return input.readInt() == 0x89504E47 && input.readInt() == 0x0D0A1A0A
				&& input.readInt() == 13 && input.readInt() == 0x49484452
				&& input.readInt() == 64 && input.readInt() == 64;
		} catch (IOException invalidPng) {
			return false;
		}
	}
}
