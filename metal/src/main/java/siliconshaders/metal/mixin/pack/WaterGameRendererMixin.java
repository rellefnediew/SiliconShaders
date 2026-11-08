package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.own.WaterRuntime;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Compose while world depth still exists: render3dHud clears it before drawing the hand. */
@Mixin(GameRenderer.class)
abstract class WaterGameRendererMixin {
    @Inject(method="renderLevel",at=@At(value="INVOKE",target="Lnet/minecraft/client/renderer/GameRenderer;render3dHud(Lnet/minecraft/client/renderer/state/level/CameraRenderState;Lnet/minecraft/client/renderer/state/level/PlayerRenderState;Lnet/minecraft/client/renderer/state/OptionsRenderState;Z)V",shift=At.Shift.BEFORE))
    private void siliconshaders$waterBeforeHud(CallbackInfo ci) {
        var target=((GameRenderer)(Object)this).mainRenderTarget();
        WaterRuntime.finish(target);
        var own=siliconshaders.metal.own.OwnTerrain.get();if(own!=null)own.measureExposure(target);
        siliconshaders.metal.camera.CameraRuntime.finish(target);
        siliconshaders.metal.sun.SunEntityLighting.end();
        // Render resolution: water and camera effects ran on the scaled world; MetalFX enlarges it before the hand.
        siliconshaders.metal.own.RenderScale.finish();
    }
    /** Render resolution: the world phase starts here (the main target is still answered at this point). */
    @Inject(method="renderLevel",at=@At("HEAD"))
    private void siliconshaders$scaleBegin(CallbackInfo ci) { siliconshaders.metal.own.RenderScale.begin(((GameRenderer)(Object)this).mainRenderTarget()); }
    /** A renderLevel that never reached the hand still shows its world. */
    @Inject(method="renderLevel",at=@At("RETURN"))
    private void siliconshaders$scaleEnd(CallbackInfo ci) { siliconshaders.metal.own.RenderScale.finish(); }
    /** The level renderer and siliconshaders read the target through this accessor; the hand, HUD and GUI read the field. */
    @Inject(method="mainRenderTarget",at=@At("HEAD"),cancellable=true)
    private void siliconshaders$scaledTarget(CallbackInfoReturnable<com.mojang.blaze3d.pipeline.RenderTarget> cir) {
        var world=siliconshaders.metal.own.RenderScale.redirect();if(world!=null)cir.setReturnValue(world);
    }
    @Inject(method="close",at=@At("HEAD"))
    private void siliconshaders$closeWater(CallbackInfo ci) { siliconshaders.metal.own.RenderScale.close();WaterRuntime.close();siliconshaders.metal.camera.CameraRuntime.close();siliconshaders.metal.sun.SunEntityLighting.end(); }
}
