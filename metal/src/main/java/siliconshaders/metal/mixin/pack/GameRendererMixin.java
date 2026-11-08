package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.pipeline.RenderTarget;
import siliconshaders.metal.pack.ShaderRuntime;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private RenderTarget mainRenderTarget;
    @Inject(method="extract",at=@At("TAIL"))
    private void siliconshaders$extractShaderInputs(DeltaTracker tracker,boolean advance,CallbackInfo ci) {
        ShaderRuntime.extract(minecraft.level!=null && minecraft.level.dimension()==Level.OVERWORLD,
            minecraft.player!=null && minecraft.player.isEyeInFluid(FluidTags.WATER)?1:0);
    }
    @Inject(method="render",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V",shift=At.Shift.AFTER))
    private void siliconshaders$shaderBeforeHud(CallbackInfo ci) { ShaderRuntime.render(mainRenderTarget); }
    @Inject(method="close",at=@At("HEAD"))
    private void siliconshaders$releaseShaders(CallbackInfo ci) { ShaderRuntime.close(); }
}
