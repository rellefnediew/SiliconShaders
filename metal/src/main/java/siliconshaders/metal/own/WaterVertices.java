package siliconshaders.metal.own;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.function.BooleanSupplier;

/** Tag actual water fluids, preserving 4-bit block/sky light in the existing BLOCK format.
 * Light U=255 is outside Minecraft's valid 0..240 light range; V holds sky/block nibbles.
 * No water classification by tint, atlas UV or transparency is needed in the shader. */
final class WaterVertices implements VertexConsumer {
    private final VertexConsumer output;
    private final BooleanSupplier water;
    private final java.util.function.IntSupplier material;
    WaterVertices(VertexConsumer output,BooleanSupplier water) { this(output,water,()->0); }
    WaterVertices(VertexConsumer output,BooleanSupplier water,java.util.function.IntSupplier material) { this.output=output;this.water=water;this.material=material; }
    // Six transparent kinds fit in the unused low light nibble. Metadata travels with every vertex through sorting/copies.
    static int encodeMaterial(int packed,int id) { return id==0?packed:(packed&0x00f000f0)|(id&15)|((id&240)<<12); }
    static int decodeMaterial(int packed) { return packed&0x00f000f0; }
    private int tag(int packed) { return water.getAsBoolean()?encode(packed):encodeMaterial(packed,material.getAsInt()); }
    static int encode(int packed) { return 255|(((packed>>>16)&240)|((packed&240)>>>4))<<16; }
    static int decode(int packed) { return ((packed>>>16)&15)<<4|((packed>>>16)&240)<<16; }
    @Override public void addVertex(float x,float y,float z,int color,float u,float v,int overlay,int light,float nx,float ny,float nz) {
        output.addVertex(x,y,z,color,u,v,overlay,tag(light),nx,ny,nz);
    }
    @Override public VertexConsumer addVertex(float x,float y,float z) { output.addVertex(x,y,z);return this; }
    @Override public VertexConsumer setColor(int r,int g,int b,int a) { output.setColor(r,g,b,a);return this; }
    @Override public VertexConsumer setColor(int color) { output.setColor(color);return this; }
    @Override public VertexConsumer setUv(float u,float v) { output.setUv(u,v);return this; }
    @Override public VertexConsumer setUv1(int u,int v) { output.setUv1(u,v);return this; }
    @Override public VertexConsumer setUv2(int u,int v) {
        int encoded=tag((u&65535)|(v<<16));
        output.setUv2(encoded&65535,encoded>>>16);return this;
    }
    @Override public VertexConsumer setUv3(float u,float v) { output.setUv3(u,v);return this; }
    @Override public VertexConsumer setNormal(float x,float y,float z) { output.setNormal(x,y,z);return this; }
    @Override public VertexConsumer setLineWidth(float width) { output.setLineWidth(width);return this; }
}
