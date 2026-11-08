package siliconshaders.metal.sun;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** Final rain color/opacity and UVs. Applied at the consumer so Lunar's weather color cannot overwrite the sliders. */
final class RainVertices implements VertexConsumer {
    private VertexConsumer target;private final RainMotion motion;private float y;
    RainVertices(RainMotion motion){this.motion=motion;}
    VertexConsumer bind(VertexConsumer target){this.target=target;return this;}
    void clear(){target=null;}
    public VertexConsumer addVertex(float x,float y,float z){this.y=y;target.addVertex(x,y,z);return this;}
    public VertexConsumer setUv(float u,float v){target.setUv(motion.u(u,y),motion.v(v));return this;}
    public VertexConsumer setColor(int color){target.setColor(SunRain.column(color));return this;}
    public VertexConsumer setColor(int r,int g,int b,int a){return setColor((a&255)<<24|(r&255)<<16|(g&255)<<8|(b&255));}
    public VertexConsumer setUv1(int u,int v){target.setUv1(u,v);return this;}
    public VertexConsumer setUv2(int u,int v){target.setUv2(u,v);return this;}
    public VertexConsumer setUv3(float u,float v){target.setUv3(u,v);return this;}
    public VertexConsumer setNormal(float x,float y,float z){target.setNormal(x,y,z);return this;}
    public VertexConsumer setLineWidth(float w){target.setLineWidth(w);return this;}
}
