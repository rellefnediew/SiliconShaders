package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.List;
import siliconshaders.metal.sun.SunSettings;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Suppress vanilla blob shadows while the native shader is enabled, preserving the user's saved option. */
@Mixin(EntityRenderDispatcher.class)
abstract class VanillaShadowMixin {
    @Redirect(method="submit",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/SubmitNodeCollector;submitShadow(Lcom/mojang/blaze3d/vertex/PoseStack;FLjava/util/List;)V"))
    private void siliconshaders$nativeShadows(SubmitNodeCollector collector,PoseStack pose,float radius,List<EntityRenderState.ShadowPiece> pieces) {
        if(!SunSettings.get().state().enabled()) collector.submitShadow(pose,radius,pieces);
    }
}
