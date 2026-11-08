package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.sun.SunAtmosphere;
import siliconshaders.metal.sun.SunCelestial;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replace the two celestial sprites and, with the shader's own, vanilla's stars; keep sky geometry and the resource-pack fallback. */
@Mixin(SkyRenderer.class)
abstract class SunCelestialMixin {
    @Inject(method={"renderSun","renderMoon"},at=@At("HEAD"),cancellable=true)
    private void siliconshaders$customBodies(CallbackInfo ci){if(SunCelestial.active())ci.cancel();}
    @Inject(method="extractRenderState",at=@At("RETURN"))
    private void siliconshaders$ownStars(ClientLevel level,float partialTick,Camera camera,SkyRenderState state,CallbackInfo ci){if(SunAtmosphere.ownStars())state.starBrightness=0;}
}
