package siliconshaders.metal.fast;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3x2fc;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector3f;

/**
 * GUI text glyphs (BakedSheetGlyph.render) and image quads (BlitRenderState.buildVertices) as whole-vertex writes
 * (FastVertices). Corners, transforms (the same Matrix4fc/Matrix3x2fc.transformPosition the chained calls use), uv,
 * color and light match vanilla byte for byte. Other consumers or formats return false and the caller draws the
 * vanilla way. -Dsiliconshaders.fast.gui=false keeps vanilla.
 */
public final class FastGui {
    public static final boolean ON=!"false".equals(System.getProperty("siliconshaders.fast.gui"));
    private FastGui() {}

    /** One glyph quad: the four corners BakedSheetGlyph.render emits (shear from italics, 0.1 extra for bold). */
    public static boolean glyph(float left,float right,float up,float down,float shearTop,float shearBottom,float u0,float u1,float v0,float v1,
                                float x,float y,float z,Matrix4fc pose,VertexConsumer consumer,int color,boolean bold,int light) {
        if(!ON)return false;
        BufferBuilder builder=FastVertices.direct(consumer,FastVertices.TEXT);
        if(builder==null)return false;
        float x0=x+left,x1=x+right,y0=y+up,y1=y+down,t=bold?.1f:0f;int rgba=FastVertices.rgba(color);var layout=FastVertices.TEXT;
        Vector3f p=new Vector3f();
        pose.transformPosition(x0+shearTop-t,y0-t,z,p);FastVertices.put(builder,layout,p.x,p.y,p.z,u0,v0,rgba,light);
        pose.transformPosition(x0+shearBottom-t,y1+t,z,p);FastVertices.put(builder,layout,p.x,p.y,p.z,u0,v1,rgba,light);
        pose.transformPosition(x1+shearBottom+t,y1+t,z,p);FastVertices.put(builder,layout,p.x,p.y,p.z,u1,v1,rgba,light);
        pose.transformPosition(x1+shearTop+t,y0-t,z,p);FastVertices.put(builder,layout,p.x,p.y,p.z,u1,v0,rgba,light);
        return true;
    }

    /** One image quad: corners (x0,y0) (x0,y1) (x1,y1) (x1,y0), as BlitRenderState.buildVertices emits them. */
    public static boolean blit(Matrix3x2fc pose,int x0,int y0,int x1,int y1,float u0,float u1,float v0,float v1,int color,VertexConsumer consumer) {
        if(!ON)return false;
        BufferBuilder builder=FastVertices.direct(consumer,FastVertices.IMAGE);
        if(builder==null)return false;
        int rgba=FastVertices.rgba(color);var layout=FastVertices.IMAGE;Vector2f p=new Vector2f();
        pose.transformPosition(x0,y0,p);FastVertices.put(builder,layout,p.x,p.y,0,u0,v0,rgba,0);
        pose.transformPosition(x0,y1,p);FastVertices.put(builder,layout,p.x,p.y,0,u0,v1,rgba,0);
        pose.transformPosition(x1,y1,p);FastVertices.put(builder,layout,p.x,p.y,0,u1,v1,rgba,0);
        pose.transformPosition(x1,y0,p);FastVertices.put(builder,layout,p.x,p.y,0,u1,v0,rgba,0);
        return true;
    }
}
