package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.own.WaterRuntime;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Replace water's environmental fog only once the native post pass is available.
 * Blindness/darkness, lava and powder snow keep their original behavior. */
@Mixin(FogRenderer.class)
abstract class WaterFogMixin {
    @Inject(method="setupFog",at=@At("RETURN"))
    private void siliconshaders$waterFog(Camera camera,int distance,DeltaTracker tracker,float darkening,ClientLevel level,CallbackInfoReturnable<FogData> cir) {
        if(camera.getFluidInCamera()!=FogType.WATER)return;
        FogData fog=cir.getReturnValue();
        // Use the biome tint before vanilla's time-dependent water-vision brightening.
        WaterRuntime.captureFog(camera.attributeProbe().getValue(net.minecraft.world.attribute.EnvironmentAttributes.WATER_FOG_COLOR,
            tracker.getGameTimeDeltaPartialTick(false)));
        if(!WaterRuntime.replacesFog())return;
        if(camera.entity() instanceof LivingEntity living&&(living.hasEffect(MobEffects.BLINDNESS)||living.hasEffect(MobEffects.DARKNESS)))return;
        fog.environmentalStart=1_000_000;fog.environmentalEnd=2_000_000;
    }
}
