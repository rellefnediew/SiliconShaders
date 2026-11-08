package siliconshaders.metal.sun;

/** CPU-only rain texture coordinates: fixed column identities, per-frame wind, no geometry or roof-mask changes. */
public final class RainMotion {
    public static final int WIND=SunTuning.index("rainWind"),ANGLE=SunTuning.index("rainAngle"),DIRECTION=SunTuning.index("rainDirection"),
        GUSTS=SunTuning.index("rainGusts"),SPEED=SunTuning.index("rainWindSpeed"),WIDTH=SunTuning.index("dropWidth"),
        LENGTH=SunTuning.index("dropLength"),VARIATION=SunTuning.index("dropVariation");
    private float windX,windZ,width,length,variation,columnWidth,columnLength,shear;
    private double cameraX,cameraY,cameraZ;
    public void frame(float[] tuning,long ticks,float partial,double x,double y,double z) {
        cameraX=x;cameraY=y;cameraZ=z;width=tuning[WIDTH];length=tuning[LENGTH];variation=tuning[VARIATION];
        double t=(ticks+(Float.isFinite(partial)?partial:0))/20.0*tuning[SPEED];
        double gust=tuning[GUSTS]*(.65*Math.sin(t*.43)+.35*Math.sin(t*.79+1.7));
        double bearing=Math.toRadians(tuning[DIRECTION])+gust*.12;
        double slope=tuning[WIND]>.5?Math.tan(Math.toRadians(tuning[ANGLE]))*(1+gust*.45):0;
        windX=(float)(Math.sin(bearing)*slope);windZ=(float)(-Math.cos(bearing)*slope);
        columnWidth=width;columnLength=length;shear=0;
    }
    public boolean changesVertices(){return windX!=0||windZ!=0||width!=1||length!=1||variation!=0;}
    public void column(float relativeX,float relativeZ) {
        int x=(int)Math.floor(cameraX+relativeX),z=(int)Math.floor(cameraZ+relativeZ);
        int h=x*0x632be5ab^z*0x85157af5;h^=h>>>16;h*=0x7feb352d;h^=h>>>15;h*=0x846ca68b;h^=h>>>16;
        columnWidth=width*(1+variation*((h&65535)/32767.5f-1));
        columnLength=length*(1+variation*((h>>>16)/32767.5f-1));
        // Vanilla's camera-facing column tangent (-z,x). Project world wind onto that plane.
        float r=relativeX*relativeX+relativeZ*relativeZ;
        shear=r>1e-8f?(windZ*relativeX-windX*relativeZ)/(float)Math.sqrt(r):0;
    }
    // Shear uses actual height, not the scrolling V coordinate: texture animation then moves diagonally along the drops.
    // A fixed world anchor avoids rain swimming when the camera walks or turns. Geometry/depth and roof clipping stay vanilla.
    public float u(float u,float relativeY){return (u+shear*(float)(cameraY+relativeY-64))/columnWidth;}
    public float v(float v){return v/columnLength;}
}
