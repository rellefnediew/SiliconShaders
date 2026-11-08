package siliconshaders.metal.mixin.fast;

import com.mojang.blaze3d.vertex.VertexConsumer;
import siliconshaders.metal.fast.FastFluidLight;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Smooth fluid lighting (FastFluidLight): per-corner light instead of one value per face. */
@Mixin(FluidRenderer.class)
abstract class FastFluidLightMixin {
    @Shadow private void vertex(VertexConsumer consumer,float x,float y,float z,int color,float u,float v,int light) { throw new AssertionError(); }
    @Shadow private int getLightCoords(BlockAndTintGetter level,BlockPos pos) { throw new AssertionError(); }

    @Inject(method="tesselate",at=@At("HEAD"))
    private void siliconshaders$fluidBegin(BlockAndTintGetter level,BlockPos pos,FluidRenderer.Output output,net.minecraft.world.level.block.state.BlockState state,
                                  net.minecraft.world.level.material.FluidState fluid,CallbackInfo ci) { FastFluidLight.begin(level,pos); }
    @Inject(method="tesselate",at=@At("RETURN"))
    private void siliconshaders$fluidEnd(BlockAndTintGetter level,BlockPos pos,FluidRenderer.Output output,net.minecraft.world.level.block.state.BlockState state,
                                net.minecraft.world.level.material.FluidState fluid,CallbackInfo ci) { FastFluidLight.end(); }

    @Inject(method="addFace",at=@At("HEAD"),cancellable=true)
    private void siliconshaders$smoothFace(VertexConsumer c,float x0,float y0,float z0,float u0,float v0,float x1,float y1,float z1,float u1,float v1,
                                  float x2,float y2,float z2,float u2,float v2,float x3,float y3,float z3,float u3,float v3,int color,int light,boolean twoSided,CallbackInfo ci) {
        FastFluidLight.Context ctx=FastFluidLight.context();
        if(ctx==null)return;
        float ax=x1-x0,ay=y1-y0,az=z1-z0,bx=x2-x0,by=y2-y0,bz=z2-z0;
        int axis=FastFluidLight.axis(ay*bz-az*by,az*bx-ax*bz,ax*by-ay*bx);
        int layer=FastFluidLight.layer(ctx,axis,axis==0?x0:axis==1?y0:z0);
        int l0=this.siliconshaders$corner(ctx,axis,layer,x0,y0,z0,light),l1=this.siliconshaders$corner(ctx,axis,layer,x1,y1,z1,light);
        int l2=this.siliconshaders$corner(ctx,axis,layer,x2,y2,z2,light),l3=this.siliconshaders$corner(ctx,axis,layer,x3,y3,z3,light);
        this.vertex(c,x0,y0,z0,color,u0,v0,l0);this.vertex(c,x1,y1,z1,color,u1,v1,l1);this.vertex(c,x2,y2,z2,color,u2,v2,l2);this.vertex(c,x3,y3,z3,color,u3,v3,l3);
        if(twoSided){this.vertex(c,x0,y0,z0,color,u0,v0,l0);this.vertex(c,x3,y3,z3,color,u3,v3,l3);this.vertex(c,x2,y2,z2,color,u2,v2,l2);this.vertex(c,x1,y1,z1,color,u1,v1,l1);}
        ci.cancel();
    }

    /** The four blocks around a corner in the face's layer; full opaque blocks do not count; none left: the face's vanilla light. */
    private int siliconshaders$corner(FastFluidLight.Context ctx,int axis,int layer,float lx,float ly,float lz,int fallback) {
        double wx=ctx.ox+lx,wy=ctx.oy+ly,wz=ctx.oz+lz;
        int ta=axis==0?(int)Math.floor(wy-.5):(int)Math.floor(wx-.5),tb=axis==2?(int)Math.floor(wy-.5):(int)Math.floor(wz-.5);
        int blockSum=0,skySum=0,n=0;
        for(int i=0;i<2;i++)for(int j=0;j<2;j++) {
            int a=ta+i,b=tb+j;
            if(axis==0)ctx.sample.set(layer,a,b);else if(axis==1)ctx.sample.set(a,layer,b);else ctx.sample.set(a,b,layer);
            if(ctx.level.getBlockState(ctx.sample).isSolidRender())continue;
            int l=this.getLightCoords(ctx.level,ctx.sample);blockSum+=l&0xFFFF;skySum+=l>>>16;n++;
        }
        return n==0?fallback:FastFluidLight.average(blockSum,skySum,n);
    }
}
