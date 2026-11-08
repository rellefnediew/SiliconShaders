package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.sun.SunLighting;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class SunLightingTickMixin {
    @Inject(method="tick",at=@At("TAIL"))
    private void siliconshaders$lightingChanged(CallbackInfo ci) { SunLighting.tick((Minecraft)(Object)this); }
}
