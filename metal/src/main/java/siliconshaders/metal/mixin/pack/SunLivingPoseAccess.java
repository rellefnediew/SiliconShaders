package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The living-entity pose steps of LivingEntityRenderer.submit, for posing the first-person shadow body without a submit. */
@Mixin(LivingEntityRenderer.class)
public interface SunLivingPoseAccess {
    @Invoker("setupRotations") void siliconshaders$sunSetupRotations(LivingEntityRenderState state,PoseStack pose,float bodyRot,float scale);
    @Invoker("scale") void siliconshaders$sunScale(LivingEntityRenderState state,PoseStack pose);
}
