package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.sun.SunLighting;
import siliconshaders.metal.sun.SunTuning;
import siliconshaders.metal.sun.SunWeather;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** With the shader on, sun and moon stay faintly in the rainy sky (SunWeather.celestial), as their light does. */
@Mixin(SkyRenderer.class)
abstract class SunSkyRainMixin {
    @Inject(method="extractRenderState",at=@At("RETURN"))
    private void siliconshaders$sunMoonInRain(ClientLevel level,float partialTick,Camera camera,SkyRenderState state,CallbackInfo ci) {
        // Clear skies return at once; single values, not a copy of every setting each frame.
        if(state.rainBrightness>=1||!SunLighting.enabled())return;
        SunTuning tuning=SunTuning.get();
        state.rainBrightness=SunWeather.celestial(state.rainBrightness,state.sunAngle,tuning.value(SunWeather.WEATHER)>0.5f,tuning.value(SunWeather.SUN),tuning.value(SunWeather.MOON));
    }
}
