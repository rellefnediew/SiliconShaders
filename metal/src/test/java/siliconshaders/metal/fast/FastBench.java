package siliconshaders.metal.fast;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/** Throughput of the fast paths against vanilla called directly, after JIT warm-up. Informational; not part of check. */
public final class FastBench {
    static final class Vanilla extends QuadParticleRenderState {
        void quad(VertexConsumer c,float x,float y,float z,float qx,float qy,float qz,float qw,float s,float u0,float u1,float v0,float v1,int color,int light) {
            renderRotatedQuad(c,x,y,z,qx,qy,qz,qw,s,u0,u1,v0,v1,color,light);
        }
    }
    static long sink;
    public static void main(String[] args) throws Throwable {
        Vanilla vanilla=new Vanilla();int n=4000;
        for(String what:new String[]{"particle"})for(int path=0;path<2;path++) {
            long best=Long.MAX_VALUE;
            for(int round=0;round<60;round++) {
                try(ByteBufferBuilder memory=new ByteBufferBuilder(1<<22)) {
                    BufferBuilder b=new BufferBuilder(memory,PrimitiveTopology.QUADS,DefaultVertexFormat.PARTICLE);
                    long start=System.nanoTime();
                    for(int k=0;k<n;k++) {
                        {float q=k*1e-4f;if(path==0)vanilla.quad(b,k,2,3,.1f+q,.2f,.3f,.9f,.2f,0,1,0,1,-1,240);else FastParticles.quad(b,k,2,3,.1f+q,.2f,.3f,.9f,.2f,0,1,0,1,-1,240);}
                    }
                    long t=System.nanoTime()-start;if(round>=20)best=Math.min(best,t);
                    try(var mesh=b.buildOrThrow()){sink+=mesh.vertexBuffer().remaining();}
                }
            }
            System.out.printf("%s %s: %.1f ns each%n",what,path==0?"vanilla":"fast",best/(double)n);
        }
        // GUI text glyph and image quads.
        Class<?> glyphClass=net.minecraft.client.gui.font.glyphs.BakedSheetGlyph.class;
        Object glyph=glyphClass.getConstructor(com.mojang.blaze3d.font.GlyphInfo.class,net.minecraft.client.gui.font.GlyphRenderTypes.class,com.mojang.renderpearl.api.textures.GpuTextureView.class,
            float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class).newInstance(com.mojang.blaze3d.font.GlyphInfo.simple(6),null,null,0f,.1f,0f,.1f,0f,5f,0f,8f);
        var lookup=java.lang.invoke.MethodHandles.privateLookupIn(glyphClass,java.lang.invoke.MethodHandles.lookup());
        java.lang.invoke.MethodHandle render=lookup.findVirtual(glyphClass,"render",java.lang.invoke.MethodType.methodType(void.class,boolean.class,float.class,float.class,float.class,org.joml.Matrix4fc.class,VertexConsumer.class,int.class,boolean.class,int.class))
            .asType(java.lang.invoke.MethodType.methodType(void.class,Object.class,boolean.class,float.class,float.class,float.class,org.joml.Matrix4fc.class,VertexConsumer.class,int.class,boolean.class,int.class));
        org.joml.Matrix4f pose=new org.joml.Matrix4f().translate(2,3,0);org.joml.Matrix3x2f pose2=new org.joml.Matrix3x2f().translate(5,6);
        var blit=new net.minecraft.client.renderer.state.gui.BlitRenderState(null,null,pose2,10,10,26,26,0,1,0,1,-1,null);
        for(String what:new String[]{"glyph","image"})for(int path=0;path<2;path++) {
            long best=Long.MAX_VALUE;
            for(int round=0;round<60;round++) {
                try(ByteBufferBuilder memory=new ByteBufferBuilder(1<<22)) {
                    BufferBuilder b=new BufferBuilder(memory,PrimitiveTopology.QUADS,what.equals("glyph")?DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP:DefaultVertexFormat.POSITION_TEX_COLOR);
                    long start=System.nanoTime();
                    for(int k=0;k<n;k++) {
                        if(what.equals("glyph")){if(path==0)render.invokeExact(glyph,false,(float)k,2f,0f,(org.joml.Matrix4fc)pose,(VertexConsumer)b,-1,false,240);else FastGui.glyph(0,5,0,8,0,0,0,.1f,0,.1f,k,2,0,pose,b,-1,false,240);}
                        else {if(path==0)blit.buildVertices(b);else FastGui.blit(pose2,10,10,26,26,0,1,0,1,-1,b);}
                    }
                    long t=System.nanoTime()-start;if(round>=20)best=Math.min(best,t);
                    try(var mesh=b.buildOrThrow()){sink+=mesh.vertexBuffer().remaining();}
                }
            }
            System.out.printf("%s %s: %.1f ns each%n",what,path==0?"vanilla":"fast",best/(double)n);
        }
    }
}
