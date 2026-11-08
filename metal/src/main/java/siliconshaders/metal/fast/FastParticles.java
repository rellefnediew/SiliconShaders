package siliconshaders.metal.fast;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * Billboard particle quads (QuadParticleRenderState.renderRotatedQuad) without a quaternion rotation per corner and, in the
 * PARTICLE format, with whole-vertex writes (FastVertices). The rotation's two in-plane axes are computed once per particle
 * with the normalized transform JOML applies to each corner. Same corners, order, uv, color and light; positions differ
 * from vanilla only by float rounding. -Dsiliconshaders.fast.particles=false keeps the vanilla path.
 */
public final class FastParticles {
    public static final boolean ON=!"false".equals(System.getProperty("siliconshaders.fast.particles"));
    private FastParticles() {}

    /** False: the caller draws the vanilla way. */
    public static boolean quad(VertexConsumer consumer,float x,float y,float z,float qx,float qy,float qz,float qw,float size,
                               float u0,float u1,float v0,float v1,int color,int light) {
        if(!ON)return false;
        float xx=qx*qx,yy=qy*qy,zz=qz*qz,ww=qw*qw,k=1/(xx+yy+zz+ww);
        float xy=qx*qy,xz=qx*qz,yz=qy*qz,xw=qx*qw,zw=qz*qw,yw=qy*qw;
        float ax=(xx-yy-zz+ww)*k*size,ay=2*(xy+zw)*k*size,az=2*(xz-yw)*k*size;
        float bx=2*(xy-zw)*k*size,by=(yy-xx-zz+ww)*k*size,bz=2*(yz+xw)*k*size;
        // Corners (1,-1) (1,1) (-1,1) (-1,-1) with uv (u1,v1) (u1,v0) (u0,v0) (u0,v1), as renderRotatedQuad emits them.
        BufferBuilder builder=FastVertices.direct(consumer,FastVertices.PARTICLE);
        if(builder!=null) {
            int rgba=FastVertices.rgba(color);var layout=FastVertices.PARTICLE;
            FastVertices.put(builder,layout,x+ax-bx,y+ay-by,z+az-bz,u1,v1,rgba,light);
            FastVertices.put(builder,layout,x+ax+bx,y+ay+by,z+az+bz,u1,v0,rgba,light);
            FastVertices.put(builder,layout,x-ax+bx,y-ay+by,z-az+bz,u0,v0,rgba,light);
            FastVertices.put(builder,layout,x-ax-bx,y-ay-by,z-az-bz,u0,v1,rgba,light);
            return true;
        }
        consumer.addVertex(x+ax-bx,y+ay-by,z+az-bz).setUv(u1,v1).setColor(color).setLight(light);
        consumer.addVertex(x+ax+bx,y+ay+by,z+az+bz).setUv(u1,v0).setColor(color).setLight(light);
        consumer.addVertex(x-ax+bx,y-ay+by,z-az+bz).setUv(u0,v0).setColor(color).setLight(light);
        consumer.addVertex(x-ax-bx,y-ay-by,z-az-bz).setUv(u0,v1).setColor(color).setLight(light);
        return true;
    }
}
