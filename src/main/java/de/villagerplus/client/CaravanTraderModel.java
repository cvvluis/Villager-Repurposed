package de.villagerplus.client;

import com.mojang.blaze3d.vertex.PoseStack;
import de.villagerplus.VillagerPlus;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.HumanoidArm;

/** A distinct travelling merchant with practical travel gear and villager-sized proportions. */
public final class CaravanTraderModel extends EntityModel<ArmedEntityRenderState>
	implements ArmedModel<ArmedEntityRenderState> {
	public static final ModelLayerLocation LAYER = new ModelLayerLocation(
		Identifier.fromNamespaceAndPath(VillagerPlus.MOD_ID, "caravan_trader"), "main");
	static final float HEAD_TOP = -10.0F;
	static final float HEAD_WIDTH = 8.0F;
	static final float HEAD_HEIGHT = 10.0F;
	static final float HEAD_DEPTH = 8.0F;
	static final float TORSO_WIDTH = 8.0F;
	static final float TORSO_HEIGHT = 12.0F;
	static final float TORSO_DEPTH = 6.0F;
	static final float ARM_WIDTH = 4.0F;
	static final float ARM_HEIGHT = 12.0F;
	static final float ARM_DEPTH = 4.0F;
	static final float LEG_WIDTH = 4.0F;
	static final float LEG_HEIGHT = 12.0F;
	static final float LEG_DEPTH = 4.0F;
	static final float HAT_CROWN_HEIGHT = 3.0F;
	static final float HAT_BRIM_HEIGHT = 1.0F;
	static final float HAT_BAND_HEIGHT = 0.8F;
	static final float HAT_CROWN_WIDTH = 6.0F;
	static final int HAT_CROWN_U = 32;
	static final int HAT_CROWN_V = 0;
	static final float HAT_BRIM_WIDTH = 8.0F;
	static final float HAT_BRIM_DEPTH = 9.0F;
	static final float HAT_BRIM_TEXTURE_DEPTH = 4.0F;
	static final float HAT_BRIM_DEPTH_GROW = (HAT_BRIM_DEPTH - HAT_BRIM_TEXTURE_DEPTH) / 2.0F;
	static final int HAT_BRIM_U = HAT_CROWN_U;
	static final int HAT_BRIM_V = HAT_CROWN_V;
	// Keep the small human-like nose bridge on the unused skin-colored atlas corner.
	static final int NOSE_U = 0;
	static final int NOSE_V = 0;
	static final float NOSE_WIDTH = 1.0F;
	static final float NOSE_PART_Y = -2.0F;
	static final float NOSE_BOX_X = -NOSE_WIDTH / 2.0F;
	static final float NOSE_BOX_Y = -2.0F;
	static final float NOSE_BOX_Z = -5.0F;
	static final float NOSE_HEIGHT = 2.0F;
	static final float NOSE_DEPTH = 1.0F;
	static final int STRAP_U = 52;
	static final int STRAP_V = 38;
	static final int STRAP_UV_WIDTH = 3;
	static final int STRAP_UV_HEIGHT = 11;
	static final float STRAP_LEFT_X = -3.2F;
	static final float STRAP_RIGHT_X = 2.5F;
	static final float STRAP_Y = 1.0F;
	static final float STRAP_Z = -3.55F;
	static final float STRAP_WIDTH = 0.7F;
	static final float STRAP_HEIGHT = 10.0F;
	static final float STRAP_DEPTH = 0.5F;
	static final float PACK_Y = 3.0F;
	static final float COAT_DEFORMATION = 0.25F;
	static final float PACK_COAT_CLEARANCE = 0.125F;
	static final float PACK_Z = TORSO_DEPTH / 2.0F + COAT_DEFORMATION + PACK_COAT_CLEARANCE;
	static final float PACK_WIDTH = 6.0F;
	static final float PACK_HEIGHT = 8.0F;
	static final float PACK_DEPTH = 4.0F;
	static final float SIDE_POUCH_WIDTH = 0.6F;
	static final float SIDE_POUCH_HEIGHT = 3.0F;
	static final float SIDE_POUCH_DEPTH = 2.5F;
	static final float SIDE_POUCH_Y = 6.0F;
	static final float SIDE_POUCH_Z = PACK_Z + 0.75F;
	static final float SIDE_POUCH_DEFORMATION = 0.05F;
	static final float POUCH_GAP = 0.1F;
	static final float LEFT_SIDE_POUCH_X = -PACK_WIDTH / 2.0F - SIDE_POUCH_WIDTH - SIDE_POUCH_DEFORMATION - POUCH_GAP;
	static final float RIGHT_SIDE_POUCH_X = PACK_WIDTH / 2.0F + SIDE_POUCH_DEFORMATION + POUCH_GAP;
	static final float BEDROLL_WIDTH = 8.0F;
	static final float BEDROLL_HEIGHT = 2.0F;
	static final float BEDROLL_DEPTH = 2.0F;
	static final float BEDROLL_DEFORMATION = 0.1F;
	static final float BEDROLL_Y = PACK_Y - BEDROLL_HEIGHT - BEDROLL_DEFORMATION;
	static final float BEDROLL_Z = PACK_Z + 0.2F;
	static final float BACK_STRAP_LEFT_X = -2.8F;
	static final float BACK_STRAP_RIGHT_X = 2.1F;
	static final float BACK_STRAP_Y = PACK_Y;
	static final float BACK_STRAP_Z = PACK_Z + PACK_DEPTH - STRAP_DEPTH / 2.0F;
	static final int PACK_U = 32;
	static final int PACK_V = 38;
	static final int LEFT_POUCH_U = 42;
	static final int RIGHT_POUCH_U = 52;
	static final int POUCH_V = 51;
	static final int POUCH_UV_WIDTH = 9;
	static final int POUCH_UV_HEIGHT = 6;
	static final float COAT_TAIL_START_Y = 10.0F;
	static final float COAT_TAIL_LENGTH = 4.0F;
	static final float COAT_TAIL_SPLIT = 0.6F;
	static final float COAT_TAIL_WIDTH = (TORSO_WIDTH - COAT_TAIL_SPLIT) / 2.0F;
	static final float COAT_TAIL_DEPTH = 0.45F;
	static final float COAT_TAIL_DEFORMATION = 0.05F;
	static final float COAT_START_Y = 0.0F;
	static final float COAT_LENGTH = TORSO_HEIGHT;
	static final float BOOT_HEIGHT = 3.0F;
	static final float BOOT_DEFORMATION = 0.1F;
	static final float MODEL_TOP_Y = HEAD_TOP - HAT_BRIM_HEIGHT - HAT_CROWN_HEIGHT;
	static final float MODEL_BOTTOM_Y = 12.0F + LEG_HEIGHT + BOOT_DEFORMATION;
	// Keep in sync with ModEntities.CARAVAN_TRADER's 1.95-block standing dimensions.
	static final float ENTITY_HEIGHT = 1.95F;
	static final float ENTITY_FIT_SCALE = ENTITY_HEIGHT * 16.0F / (MODEL_BOTTOM_Y - MODEL_TOP_Y);
	static final float ENTITY_FIT_Y_OFFSET = MODEL_BOTTOM_Y * (1.0F - ENTITY_FIT_SCALE);
	// Keep the walking swing restrained; the held-stock arm uses a shallow, close-to-body reach.
	static final float ARM_WALK_SWING = 0.55F;
	static final float LEG_WALK_SWING = 0.72F;
	static final float HELD_ITEM_ARM_ANGLE = -0.36F;
	static final float HELD_ITEM_ARM_INWARD_ROLL = 0.04F;

	private final ModelPart root;
	private final ModelPart head;
	private final ModelPart body;
	private final ModelPart pack;
	private final ModelPart rightArm;
	private final ModelPart leftArm;
	private final ModelPart rightLeg;
	private final ModelPart leftLeg;

	public CaravanTraderModel(ModelPart root) {
		super(root);
		this.root = root;
		applyEntityFit();
		this.head = root.getChild("head");
		this.body = root.getChild("body");
		this.pack = root.getChild("pack");
		this.rightArm = root.getChild("right_arm");
		this.leftArm = root.getChild("left_arm");
		this.rightLeg = root.getChild("right_leg");
		this.leftLeg = root.getChild("left_leg");
	}

	public static LayerDefinition createBodyLayer() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();

		PartDefinition head = root.addOrReplaceChild("head",
			CubeListBuilder.create()
				.texOffs(0, 0).addBox(-HEAD_WIDTH / 2.0F, HEAD_TOP, -HEAD_DEPTH / 2.0F,
					HEAD_WIDTH, HEAD_HEIGHT, HEAD_DEPTH, new CubeDeformation(0.0F)),
			PartPose.ZERO);
		head.addOrReplaceChild("nose",
			CubeListBuilder.create().texOffs(NOSE_U, NOSE_V)
				.addBox(NOSE_BOX_X, NOSE_BOX_Y, NOSE_BOX_Z, NOSE_WIDTH, NOSE_HEIGHT, NOSE_DEPTH),
			PartPose.offset(0.0F, NOSE_PART_Y, 0.0F));
		PartDefinition hat = head.addOrReplaceChild("hat", CubeListBuilder.create(), PartPose.ZERO);
		hat.addOrReplaceChild("hat_crown",
			CubeListBuilder.create().texOffs(HAT_CROWN_U, HAT_CROWN_V)
				.addBox(-HAT_CROWN_WIDTH / 2.0F, 0.0F, -HAT_CROWN_WIDTH / 2.0F,
					HAT_CROWN_WIDTH, HAT_CROWN_HEIGHT, HAT_CROWN_WIDTH),
			PartPose.offset(0.0F, HEAD_TOP - HAT_BRIM_HEIGHT - HAT_CROWN_HEIGHT, 0.0F));
		hat.addOrReplaceChild("hat_brim",
			// Keep the brim's expanded silhouette, but map its smaller undeformed box to
			// the hat-crown fabric patch instead of sampling the coat atlas at (0, 52).
			CubeListBuilder.create().texOffs(HAT_BRIM_U, HAT_BRIM_V)
				.addBox(-HAT_BRIM_WIDTH / 2.0F, 0.0F, -HAT_BRIM_TEXTURE_DEPTH / 2.0F,
					HAT_BRIM_WIDTH, HAT_BRIM_HEIGHT, HAT_BRIM_TEXTURE_DEPTH,
					new CubeDeformation(0.0F, 0.0F, HAT_BRIM_DEPTH_GROW)),
			PartPose.offset(0.0F, HEAD_TOP - HAT_BRIM_HEIGHT, 0.0F));
		hat.addOrReplaceChild("hat_band",
			CubeListBuilder.create().texOffs(32, 12)
				.addBox(-3.1F, 0.0F, -3.1F, 6.2F, HAT_BAND_HEIGHT, 6.2F, new CubeDeformation(0.02F)),
			PartPose.offset(0.0F, HEAD_TOP - HAT_BRIM_HEIGHT - HAT_BAND_HEIGHT, 0.0F));

		PartDefinition body = root.addOrReplaceChild("body",
			CubeListBuilder.create()
				.texOffs(16, 20).addBox(-TORSO_WIDTH / 2.0F, 0.0F, -TORSO_DEPTH / 2.0F,
					TORSO_WIDTH, TORSO_HEIGHT, TORSO_DEPTH),
			PartPose.ZERO);
		body.addOrReplaceChild("field_coat",
			CubeListBuilder.create().texOffs(0, 38)
				.addBox(-TORSO_WIDTH / 2.0F, COAT_START_Y, -TORSO_DEPTH / 2.0F,
					TORSO_WIDTH, COAT_LENGTH, TORSO_DEPTH,
					new CubeDeformation(COAT_DEFORMATION)),
			PartPose.ZERO);
		body.addOrReplaceChild("split_coat_tail_left",
			CubeListBuilder.create().texOffs(0, 38)
				.addBox(-TORSO_WIDTH / 2.0F, COAT_TAIL_START_Y, -TORSO_DEPTH / 2.0F - COAT_DEFORMATION - 0.08F,
					COAT_TAIL_WIDTH, COAT_TAIL_LENGTH, COAT_TAIL_DEPTH, new CubeDeformation(COAT_TAIL_DEFORMATION)),
			PartPose.ZERO);
		body.addOrReplaceChild("split_coat_tail_right",
			CubeListBuilder.create().texOffs(16, 38)
				.addBox(COAT_TAIL_SPLIT / 2.0F, COAT_TAIL_START_Y, -TORSO_DEPTH / 2.0F - COAT_DEFORMATION - 0.08F,
					COAT_TAIL_WIDTH, COAT_TAIL_LENGTH, COAT_TAIL_DEPTH, new CubeDeformation(COAT_TAIL_DEFORMATION)),
			PartPose.ZERO);
		PartDefinition pack = root.addOrReplaceChild("pack", CubeListBuilder.create(), PartPose.ZERO);
		pack.addOrReplaceChild("stock_pack",
			CubeListBuilder.create().texOffs(PACK_U, PACK_V)
				.addBox(-PACK_WIDTH / 2.0F, PACK_Y, PACK_Z, PACK_WIDTH, PACK_HEIGHT, PACK_DEPTH),
			PartPose.ZERO);
		pack.addOrReplaceChild("bedroll",
			CubeListBuilder.create().texOffs(PACK_U, PACK_V)
				.addBox(-BEDROLL_WIDTH / 2.0F, BEDROLL_Y, BEDROLL_Z,
					BEDROLL_WIDTH, BEDROLL_HEIGHT, BEDROLL_DEPTH, new CubeDeformation(BEDROLL_DEFORMATION)),
			PartPose.ZERO);
		body.addOrReplaceChild("left_pack_strap",
			CubeListBuilder.create().texOffs(STRAP_U, STRAP_V)
				.addBox(STRAP_LEFT_X, STRAP_Y, STRAP_Z, STRAP_WIDTH, STRAP_HEIGHT, STRAP_DEPTH),
			PartPose.ZERO);
		body.addOrReplaceChild("right_pack_strap",
			CubeListBuilder.create().texOffs(STRAP_U, STRAP_V).mirror()
				.addBox(STRAP_RIGHT_X, STRAP_Y, STRAP_Z, STRAP_WIDTH, STRAP_HEIGHT, STRAP_DEPTH),
			PartPose.ZERO);
		pack.addOrReplaceChild("left_back_strap",
			CubeListBuilder.create().texOffs(STRAP_U, STRAP_V)
				.addBox(BACK_STRAP_LEFT_X, BACK_STRAP_Y, BACK_STRAP_Z,
					STRAP_WIDTH, PACK_HEIGHT, STRAP_DEPTH),
			PartPose.ZERO);
		pack.addOrReplaceChild("right_back_strap",
			CubeListBuilder.create().texOffs(STRAP_U, STRAP_V).mirror()
				.addBox(BACK_STRAP_RIGHT_X, BACK_STRAP_Y, BACK_STRAP_Z,
					STRAP_WIDTH, PACK_HEIGHT, STRAP_DEPTH),
			PartPose.ZERO);
		pack.addOrReplaceChild("left_side_pouch",
			CubeListBuilder.create().texOffs(LEFT_POUCH_U, POUCH_V)
				.addBox(LEFT_SIDE_POUCH_X, SIDE_POUCH_Y, SIDE_POUCH_Z,
					SIDE_POUCH_WIDTH, SIDE_POUCH_HEIGHT, SIDE_POUCH_DEPTH, new CubeDeformation(SIDE_POUCH_DEFORMATION)),
			PartPose.ZERO);
		pack.addOrReplaceChild("right_side_pouch",
			CubeListBuilder.create().texOffs(RIGHT_POUCH_U, POUCH_V)
				.addBox(RIGHT_SIDE_POUCH_X, SIDE_POUCH_Y, SIDE_POUCH_Z,
					SIDE_POUCH_WIDTH, SIDE_POUCH_HEIGHT, SIDE_POUCH_DEPTH, new CubeDeformation(SIDE_POUCH_DEFORMATION)),
			PartPose.ZERO);

		root.addOrReplaceChild("right_arm",
			CubeListBuilder.create().texOffs(44, 22)
				.addBox(-3.0F, -2.0F, -2.0F, ARM_WIDTH, ARM_HEIGHT, ARM_DEPTH, new CubeDeformation(0.05F)),
			PartPose.offset(-5.0F, 2.0F, 0.0F));
		root.addOrReplaceChild("left_arm",
			CubeListBuilder.create().texOffs(44, 22).mirror()
				.addBox(-1.0F, -2.0F, -2.0F, ARM_WIDTH, ARM_HEIGHT, ARM_DEPTH, new CubeDeformation(0.05F)),
			PartPose.offset(5.0F, 2.0F, 0.0F));
		PartDefinition rightLeg = root.addOrReplaceChild("right_leg",
			CubeListBuilder.create().texOffs(0, 22)
				.addBox(-2.0F, 0.0F, -2.0F, LEG_WIDTH, LEG_HEIGHT, LEG_DEPTH),
			PartPose.offset(-2.0F, 12.0F, 0.0F));
		PartDefinition leftLeg = root.addOrReplaceChild("left_leg",
			CubeListBuilder.create().texOffs(0, 22).mirror()
				.addBox(-2.0F, 0.0F, -2.0F, LEG_WIDTH, LEG_HEIGHT, LEG_DEPTH),
			PartPose.offset(2.0F, 12.0F, 0.0F));
		rightLeg.addOrReplaceChild("field_boot",
			CubeListBuilder.create().texOffs(0, 22)
				.addBox(-2.0F, LEG_HEIGHT - BOOT_HEIGHT, -2.0F, LEG_WIDTH, BOOT_HEIGHT, LEG_DEPTH,
					new CubeDeformation(BOOT_DEFORMATION)),
			PartPose.ZERO);
		leftLeg.addOrReplaceChild("field_boot",
			CubeListBuilder.create().texOffs(0, 22).mirror()
				.addBox(-2.0F, LEG_HEIGHT - BOOT_HEIGHT, -2.0F, LEG_WIDTH, BOOT_HEIGHT, LEG_DEPTH,
					new CubeDeformation(BOOT_DEFORMATION)),
			PartPose.ZERO);
		return LayerDefinition.create(mesh, 64, 64);
	}

	@Override
	public void setupAnim(ArmedEntityRenderState state) {
		applyEntityFit();
		head.xRot = state.xRot * (float) (Math.PI / 180.0);
		head.yRot = state.yRot * (float) (Math.PI / 180.0);
		head.zRot = 0.0F;
		body.xRot = 0.0F;
		body.yRot = 0.0F;
		body.zRot = 0.0F;
		pack.xRot = 0.0F;
		pack.yRot = 0.0F;
		pack.zRot = 0.0F;

		float stride = state.walkAnimationPos * 0.6662F;
		float amount = Math.min(state.walkAnimationSpeed, 1.0F);
		rightArm.xRot = (float) Math.cos(stride + Math.PI) * ARM_WALK_SWING * amount;
		leftArm.xRot = (float) Math.cos(stride) * ARM_WALK_SWING * amount;
		if (!state.rightHandItemStack.isEmpty()) rightArm.xRot = HELD_ITEM_ARM_ANGLE;
		if (!state.leftHandItemStack.isEmpty()) leftArm.xRot = HELD_ITEM_ARM_ANGLE;
		rightArm.zRot = state.rightHandItemStack.isEmpty() ? 0.0F : -HELD_ITEM_ARM_INWARD_ROLL;
		leftArm.zRot = state.leftHandItemStack.isEmpty() ? 0.0F : HELD_ITEM_ARM_INWARD_ROLL;
		rightLeg.xRot = (float) Math.cos(stride) * LEG_WALK_SWING * amount;
		leftLeg.xRot = (float) Math.cos(stride + Math.PI) * LEG_WALK_SWING * amount;
		rightArm.yRot = 0.0F;
		leftArm.yRot = 0.0F;
		rightLeg.yRot = 0.0F;
		leftLeg.yRot = 0.0F;
		rightLeg.zRot = 0.0F;
		leftLeg.zRot = 0.0F;
	}

	@Override
	public void translateToHand(ArmedEntityRenderState state, HumanoidArm arm, PoseStack poseStack) {
		applyEntityFit();
		this.root.translateAndRotate(poseStack);
		ModelPart armPart = arm == HumanoidArm.LEFT ? this.leftArm : this.rightArm;
		armPart.translateAndRotate(poseStack);
		// The hand layer inherits the same uniform fit as the body, keeping held stock proportional.
	}

	private void applyEntityFit() {
		this.root.y = ENTITY_FIT_Y_OFFSET;
		this.root.xScale = ENTITY_FIT_SCALE;
		this.root.yScale = ENTITY_FIT_SCALE;
		this.root.zScale = ENTITY_FIT_SCALE;
	}
}
