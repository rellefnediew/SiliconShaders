package siliconshaders.metal.fast;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.function.Consumer;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;

/** The fast particle and GUI paths against the actual vanilla methods (no mixins in this process), without Minecraft. */
public final class FastChecks {
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}

    private static byte[] build(VertexFormat format,Consumer<BufferBuilder> writer) {
        try(ByteBufferBuilder memory=new ByteBufferBuilder(1<<16)) {
            BufferBuilder builder=new BufferBuilder(memory,PrimitiveTopology.QUADS,format);writer.accept(builder);
            try(var mesh=builder.buildOrThrow()) {
                ByteBuffer data=mesh.vertexBuffer();byte[] bytes=new byte[data.remaining()];data.get(bytes);return bytes;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        Random random=new Random(26031031);
        // Particles: same corners, uv, color and light; positions within float rounding.
        QuadParticleRenderState state=new QuadParticleRenderState();
        Method rotated=QuadParticleRenderState.class.getDeclaredMethod("renderRotatedQuad",com.mojang.blaze3d.vertex.VertexConsumer.class,
            float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class,int.class,int.class);
        rotated.setAccessible(true);
        float worst=0;int particles=0;
        for(int i=0;i<2000;i++) {
            org.joml.Quaternionf q=new org.joml.Quaternionf().rotateXYZ(random.nextFloat()*7,random.nextFloat()*7,random.nextFloat()*7);
            if(i%4==1)q.scale(.6f+random.nextFloat()); // non-unit, as JOML normalizes in transform
            float x=random.nextFloat()*200-100,y=random.nextFloat()*200-100,z=random.nextFloat()*200-100,size=random.nextFloat()*.5f;
            float u0=random.nextFloat(),u1=random.nextFloat(),v0=random.nextFloat(),v1=random.nextFloat();int color=random.nextInt(),light=random.nextInt();
            byte[] vanilla=build(DefaultVertexFormat.PARTICLE,b->{try{rotated.invoke(state,b,x,y,z,q.x,q.y,q.z,q.w,size,u0,u1,v0,v1,color,light);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
            byte[] fast=build(DefaultVertexFormat.PARTICLE,b->require(FastParticles.quad(b,x,y,z,q.x,q.y,q.z,q.w,size,u0,u1,v0,v1,color,light),"Fast particle path declined"));
            require(vanilla.length==fast.length&&vanilla.length==4*28,"Particle vertex count or size differs");
            ByteBuffer a=ByteBuffer.wrap(vanilla).order(java.nio.ByteOrder.nativeOrder()),b=ByteBuffer.wrap(fast).order(java.nio.ByteOrder.nativeOrder());
            for(int v=0;v<4;v++) {
                for(int c=0;c<3;c++){float d=Math.abs(a.getFloat(v*28+c*4)-b.getFloat(v*28+c*4));worst=Math.max(worst,d);require(d<=2e-5f*Math.max(1,Math.abs(a.getFloat(v*28+c*4))),"Particle corner moved (particle "+i+")");}
                for(int o=12;o<28;o+=4)require(a.getInt(v*28+o)==b.getInt(v*28+o),"Particle uv/color/light differs (particle "+i+")");
            }
            particles++;
        }
        // GUI text glyphs: byte-identical, plain/italic/bold, translated and scaled poses.
        Class<?> glyphClass=net.minecraft.client.gui.font.glyphs.BakedSheetGlyph.class;
        var glyphMake=glyphClass.getConstructor(com.mojang.blaze3d.font.GlyphInfo.class,net.minecraft.client.gui.font.GlyphRenderTypes.class,com.mojang.renderpearl.api.textures.GpuTextureView.class,
            float.class,float.class,float.class,float.class,float.class,float.class,float.class,float.class);
        Method glyphRender=glyphClass.getDeclaredMethod("render",boolean.class,float.class,float.class,float.class,org.joml.Matrix4fc.class,com.mojang.blaze3d.vertex.VertexConsumer.class,int.class,boolean.class,int.class);
        glyphRender.setAccessible(true);
        Method shearTop=glyphClass.getDeclaredMethod("shearTop"),shearBottom=glyphClass.getDeclaredMethod("shearBottom");shearTop.setAccessible(true);shearBottom.setAccessible(true);
        java.util.Map<String,Float> field=new java.util.HashMap<>();int glyphs=0;
        for(int i=0;i<1500;i++) {
            Object glyph=glyphMake.newInstance(com.mojang.blaze3d.font.GlyphInfo.simple(6),null,null,random.nextFloat(),random.nextFloat(),random.nextFloat(),random.nextFloat(),
                random.nextFloat()*2-1,random.nextFloat()*8,random.nextFloat()*2-1,random.nextFloat()*9);
            for(String name:new String[]{"u0","u1","v0","v1","left","right","up","down"}){var f=glyphClass.getDeclaredField(name);f.setAccessible(true);field.put(name,f.getFloat(glyph));}
            boolean italic=i%2==1,bold=i%3==2;float x=random.nextFloat()*300,y=random.nextFloat()*200,z=random.nextFloat();int color=random.nextInt(),light=random.nextInt();
            org.joml.Matrix4f pose=new org.joml.Matrix4f().translate(random.nextFloat()*5,random.nextFloat()*5,random.nextFloat());if(i%4==0)pose.scale(1.5f+random.nextFloat());
            byte[] vanilla=build(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,b->{try{glyphRender.invoke(glyph,italic,x,y,z,pose,b,color,bold,light);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}});
            float st=italic?(float)shearTop.invoke(glyph):0,sb=italic?(float)shearBottom.invoke(glyph):0;
            byte[] fast=build(DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,b->require(FastGui.glyph(field.get("left"),field.get("right"),field.get("up"),field.get("down"),st,sb,
                field.get("u0"),field.get("u1"),field.get("v0"),field.get("v1"),x,y,z,pose,b,color,bold,light),"Fast glyph path declined"));
            require(java.util.Arrays.equals(vanilla,fast),"Fast glyph differs from vanilla (glyph "+i+")");glyphs++;
        }
        // GUI images: byte-identical with rotated/scaled 2D poses.
        int blits=0;
        for(int i=0;i<1500;i++) {
            org.joml.Matrix3x2f pose=new org.joml.Matrix3x2f().translate(random.nextFloat()*50,random.nextFloat()*50);if(i%3==0)pose.rotate(random.nextFloat()*3);if(i%5==0)pose.scale(.5f+random.nextFloat()*2);
            int x0=random.nextInt(400),y0=random.nextInt(300),x1=x0+1+random.nextInt(64),y1=y0+1+random.nextInt(64),color=random.nextInt();
            float u0=random.nextFloat(),u1=random.nextFloat(),v0=random.nextFloat(),v1=random.nextFloat();
            var blit=new net.minecraft.client.renderer.state.gui.BlitRenderState(null,null,pose,x0,y0,x1,y1,u0,u1,v0,v1,color,null);
            byte[] vanilla=build(DefaultVertexFormat.POSITION_TEX_COLOR,blit::buildVertices);
            byte[] fast=build(DefaultVertexFormat.POSITION_TEX_COLOR,b->require(FastGui.blit(pose,x0,y0,x1,y1,u0,u1,v0,v1,color,b),"Fast blit path declined"));
            require(java.util.Arrays.equals(vanilla,fast),"Fast blit differs from vanilla (image "+i+")");blits++;
        }
        // Smooth fluid lighting: face axis, sampled layer and the light average.
        FastFluidLight.Context ctx=new FastFluidLight.Context();ctx.x=37;ctx.y=-5;ctx.z=-18;ctx.ox=32;ctx.oy=-16;ctx.oz=-32;
        require(FastFluidLight.axis(0,1,0)==1&&FastFluidLight.axis(-.9f,.2f,.1f)==0&&FastFluidLight.axis(.1f,-.3f,.95f)==2,"Face axis");
        require(FastFluidLight.layer(ctx,1,(37&15)*0+(-5&15)+.89f)==-5,"Top face samples its own block");
        require(FastFluidLight.layer(ctx,1,(-5&15)+.001f)==-6,"Bottom face samples the block below");
        require(FastFluidLight.layer(ctx,0,(37&15)+.999f)==38&&FastFluidLight.layer(ctx,0,(37&15)+.001f)==36,"Side faces sample the neighbour");
        require(FastFluidLight.layer(ctx,2,(-18&15)+.999f)==-17&&FastFluidLight.layer(ctx,2,(-18&15)+.001f)==-19,"Z side faces sample the neighbour");
        int full=240|240<<16,dark=0;
        require(FastFluidLight.average(240*2,240*2,2)==full&&FastFluidLight.average(240,0,2)==(120|0<<16)&&FastFluidLight.average(0,0,4)==dark,"Light average");
        require(FastFluidLight.average(240+160+80,15*16*3,3)==(160|240<<16),"Light average per channel");
        System.out.printf("PASS: %d particles with identical uv/color/light against vanilla, largest corner difference %.2e; %d text glyphs (plain/italic/bold) and %d GUI images byte-identical to vanilla%n",particles,worst,glyphs,blits);
    }
}
