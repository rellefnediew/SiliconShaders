package siliconshaders.metal.mixin.fast;

import com.mojang.blaze3d.vertex.VertexConsumer;
import siliconshaders.metal.fast.FastParticles;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Billboard particles with one rotation per particle and direct PARTICLE-format writes (FastParticles). */
@Mixin(QuadParticleRenderState.class)
abstract class FastParticleMixin {
    @Inject(method="renderRotatedQuad",at=@At("HEAD"),cancellable=true)
    private void siliconshaders$fastQuad(VertexConsumer consumer,float x,float y,float z,float qx,float qy,float qz,float qw,float size,
                                float u0,float u1,float v0,float v1,int color,int light,CallbackInfo ci) {
        if(FastParticles.quad(consumer,x,y,z,qx,qy,qz,qw,size,u0,u1,v0,v1,color,light))ci.cancel();
    }
}
