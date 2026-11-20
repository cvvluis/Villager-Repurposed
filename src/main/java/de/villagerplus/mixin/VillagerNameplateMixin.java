package de.villagerplus.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import de.villagerplus.village.Names;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Compact two-line village nameplate, depth-tested and limited to 24 blocks. */
@Mixin(EntityRenderer.class)
public abstract class VillagerNameplateMixin {
 @Shadow public abstract Font getFont();
 @Inject(method="extractRenderState",at=@At("RETURN"))
 private void vp$plate(Entity entity,EntityRenderState state,float partial,CallbackInfo ci) {
  if(!(entity instanceof Villager v) || !v.hasCustomName() || v.isInvisible() || state.distanceToCameraSq>576)return;
  state.nameTag=Component.literal("  "+v.getCustomName().getString()+"  ").withColor(0xFFE8AF);
  state.scoreText=Component.translatable("gui.villagerplus.nameplate_level",v.isBaby()?Component.translatable("gui.villagerplus.nameplate_apprentice"):Names.professionComponent(v.getVillagerData().profession()),"★".repeat(Math.max(1,Math.min(5,v.getVillagerData().level()))))
   .withColor(v.getHealth()<v.getMaxHealth()/2?0xF1A58A:0xBBD4B0);
  if(state.nameTagAttachment==null)state.nameTagAttachment=new net.minecraft.world.phys.Vec3(0,v.getBbHeight()+.5,0);
 }
 @Inject(method="submitNameDisplay(Lnet/minecraft/client/renderer/entity/state/EntityRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",at=@At("HEAD"),cancellable=true)
 private void vp$draw(EntityRenderState state,PoseStack pose,SubmitNodeCollector out,CameraRenderState camera,CallbackInfo ci) {
  if(state.entityType!=EntityTypes.VILLAGER || state.nameTag==null || state.scoreText==null || state.distanceToCameraSq>576 || state.nameTagAttachment==null)return;
  pose.pushPose();var at=state.nameTagAttachment;pose.translate(at.x,at.y+.15,at.z);pose.mulPose(camera.orientation);pose.scale(.0225F,-.0225F,.0225F);
  Font font=getFont();
  out.submitText(pose,-font.width(state.nameTag)/2F,-12,state.nameTag.getVisualOrderText(),false,Font.DisplayMode.NORMAL,state.lightCoords,0xFFFFE8AF,0xDE30271D,0);
  out.submitText(pose,-font.width(state.scoreText)/2F,0,state.scoreText.getVisualOrderText(),false,Font.DisplayMode.NORMAL,state.lightCoords,0xFFBBD4B0,0xDE30271D,0);
  pose.popPose();ci.cancel();
 }
}
