package siliconshaders.metal.mixin.pack;

import com.llamalad7.mixinextras.injector.WrapWithCondition;
import siliconshaders.metal.sun.SunRain;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Vanilla's rain splashes on the ground: SunRain.splash decides; the rain sounds in the same tick stay. */
@Mixin(ClientLevel.class)
abstract class SunRainSplashMixin {
    @WrapWithCondition(method="tickWeatherEffects",at=@At(value="INVOKE",target="Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private boolean siliconshaders$rainSplash(ClientLevel level,ParticleOptions particle,double x,double y,double z,double dx,double dy,double dz) {
        return SunRain.splash(particle);
    }
}
