package siliconshaders.metal.mixin.pack;

import com.mojang.blaze3d.systems.RenderSystem;
import siliconshaders.metal.sun.SunEntities;
import siliconshaders.metal.sun.SunLighting;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FeatureRenderDispatcher.class)
abstract class SunFeatureFrameMixin {
    /**
     * Only the game's own world dispatcher, and our first-person body while it is prepared. Other dispatchers, such as
     * ones Lunar Client prepares during the level pass, would otherwise reset the frame's casters and re-run the body.
     */
    private boolean siliconshaders$ownsCasters() {
        if(siliconshaders.metal.sun.SunSelfShadow.preparing)return true;
        var renderer=net.minecraft.client.Minecraft.getInstance().gameRenderer;
        return renderer!=null&&renderer.featureRenderDispatcher()==(Object)this;
    }
    @Inject(method="prepareFrame",at=@At("HEAD"))
    private void siliconshaders$startCasters(SubmitNodeStorage storage,CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> cir) {
        if(!this.siliconshaders$ownsCasters())return;
        if(RenderSystem.isRenderingLevel)siliconshaders.metal.sun.SunSelfShadow.source((FeatureRenderDispatcher)(Object)this);
        SunEntities.begin(RenderSystem.isRenderingLevel&&SunLighting.enabled());
    }
    @Inject(method="prepareFrame",at=@At("RETURN"))
    private void siliconshaders$endCasters(SubmitNodeStorage storage,CallbackInfoReturnable<FeatureRenderDispatcher.PreparedFrame> cir) {
        if(!this.siliconshaders$ownsCasters())return;
        SunEntities.collecting=false;
        if(RenderSystem.isRenderingLevel&&SunLighting.enabled()&&!siliconshaders.metal.sun.SunSelfShadow.preparing) {
            var level=((SunFeatureDispatcherAccess)(Object)this).siliconshaders$sunGameState().levelRenderState;
            // The first-person body also casts moon shadows at night.
            boolean night=siliconshaders.metal.sun.SunTuning.get().values()[siliconshaders.metal.sun.SunTuning.MOON_SHADOWS]>0.5f;
            if(level.cameraRenderState!=null&&(Math.cos(level.skyRenderState.sunAngle)>0.03||night))
                siliconshaders.metal.sun.SunSelfShadow.prepare(level.cameraRenderState);
        }
    }
}
