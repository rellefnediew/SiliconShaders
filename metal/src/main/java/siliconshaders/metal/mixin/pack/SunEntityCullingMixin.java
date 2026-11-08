package siliconshaders.metal.mixin.pack;

import siliconshaders.metal.sun.SunSettings;
import siliconshaders.metal.sun.SunCulling;
import net.minecraft.client.Minecraft;
import net.minecraft.world.attribute.EnvironmentAttributes;
import org.joml.Matrix4f;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep nearby offscreen casters in the shared posed geometry; the camera draw clips them normally. */
@Mixin(EntityRenderDispatcher.class)
abstract class SunEntityCullingMixin {
    @org.spongepowered.asm.mixin.Unique private static final SunCulling siliconshaders$casterFrustum=new SunCulling();
    @org.spongepowered.asm.mixin.Unique private static final Matrix4f siliconshaders$casterClip=new Matrix4f().zero();
    @org.spongepowered.asm.mixin.Unique private static float siliconshaders$casterAngle=Float.NaN,siliconshaders$casterPadding;

    @Inject(method="shouldRender",at=@At("RETURN"),cancellable=true)
    private void siliconshaders$includeCasters(Entity entity,Frustum frustum,double x,double y,double z,float partial,CallbackInfoReturnable<Boolean> cir) {
        if(cir.getReturnValueZ()||entity.isInvisible()||entity.isRemoved()||entity.isSpectator())return;
        var settings=SunSettings.get().state();
        if(!settings.enabled()||settings.shadowSize()==0||!siliconshaders.metal.sun.SunDimension.supported(entity.level().dimensionType().skybox(),false)||entity.level().dimensionType().hasCeiling())return;
        if(entity.level().dimensionType().skybox()==DimensionType.Skybox.END&&!siliconshaders.metal.sun.SunDimension.blackHole(siliconshaders.metal.sun.SunTuning.get()))return;
        double range=settings.shadowDistance();
        if(entity.distanceToSqr(x,y,z)>=range*range)return;
        if(!settings.shadowCulling()){cir.setReturnValue(true);return;}
        var camera=Minecraft.getInstance().gameRenderer.mainCamera();
        float angle=entity.level().dimensionType().skybox()==DimensionType.Skybox.END?siliconshaders.metal.sun.SunDimension.endAngle(siliconshaders.metal.sun.SunTuning.get().value(siliconshaders.metal.sun.SunDimension.END_ELEVATION)):camera.attributeProbe().getValue(EnvironmentAttributes.SUN_ANGLE,partial)*0.017453292f;
        if(!Float.isFinite(angle)){cir.setReturnValue(true);return;}
        if(Math.cos(angle)<=0.03)return;
        var access=(SunFrustumAccess)(Object)frustum;var clip=access.siliconshaders$sunClip();
        float padding=SunCulling.padding(settings.shadowSize(),settings.shadowDistance(),settings.shadowLevels());
        if(angle!=siliconshaders$casterAngle||padding!=siliconshaders$casterPadding||!siliconshaders$casterClip.equals(clip)) {
            siliconshaders$casterClip.set(clip);siliconshaders$casterAngle=angle;siliconshaders$casterPadding=padding;
            siliconshaders$casterFrustum.update(clip,-(float)Math.sin(angle),(float)Math.cos(angle),0,padding);
        }
        var renderer=((EntityRenderDispatcher)(Object)this).getRenderer(entity);
        var box=((SunEntityBoundsAccess)(Object)renderer).siliconshaders$sunBounds(entity,partial);
        if(box.hasNaN()||box.getSize()==0){cir.setReturnValue(true);return;}
        double cx=access.siliconshaders$sunX(),cy=access.siliconshaders$sunY(),cz=access.siliconshaders$sunZ();
        if(siliconshaders$casterFrustum.visible(box.minX-0.5-cx,box.minY-0.5-cy,box.minZ-0.5-cz,box.maxX+0.5-cx,box.maxY+0.5-cy,box.maxZ+0.5-cz))cir.setReturnValue(true);
    }
}
