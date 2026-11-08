package siliconshaders.metal.sun;

import org.lwjgl.system.MemoryUtil;

/** CPU-prepared overhead layers, motion, sample budget, bounded sky curvature and the sky effects' colors. */
public final class SunAurora {
    public static final int DIRECTION=SunTuning.index("auroraDirection"),ELEVATION=SunTuning.index("auroraElevation"),
        SPEED=SunTuning.index("auroraSpeed"),QUALITY=SunTuning.index("auroraQuality"),
        COUNT=SunTuning.index("auroraCount"),CURVED=SunTuning.index("auroraCurved"),CURVATURE=SunTuning.index("auroraCurvature"),EXTENT=SunTuning.index("auroraCurveExtent"),
        CURTAIN=SunTuning.index("auroraCurtainR"),CROWN=SunTuning.index("auroraCrownR"),
        DEEP=SunTuning.index("nebulaDeepR"),GLOW=SunTuning.index("nebulaGlowR"),NEBULA_SCALE=SunTuning.index("nebulaScale"),NEBULA_SPEED=SunTuning.index("nebulaSpeed"),FLOATS=28;
    private SunAurora(){}
    public static void write(long out,float[] tuning) {
        double a=Math.toRadians(tuning[DIRECTION]);
        double sa=Math.sin(a),ca=Math.cos(a);
        double height=Math.tan(Math.toRadians(tuning[ELEVATION]))/Math.tan(Math.toRadians(25));
        int count=samples(Math.round(tuning[QUALITY]));
        // H/R for a reference sky shell; strength 1 means a virtual radius of 12.5 layer heights.
        // Unused Y components of the existing packet carry the two coefficients. No ABI growth.
        double curvature=tuning[CURVED]>.5f?tuning[CURVATURE]*0.08:0;
        double inverseExtent=1/Math.sin(Math.toRadians(tuning[EXTENT]));
        put(out,sa,curvature,-ca,height);
        // Logical auroras 1..10 as the shown share of the region lattice.
        put(out+16,ca,inverseExtent,sa,Math.max(1,Math.min(10,Math.round(tuning[COUNT])))/10.0);
        put(out+32,count,1.0/count,tuning[SPEED],0);
        color(out+48,tuning,CURTAIN,0);color(out+64,tuning,CROWN,0);
        color(out+80,tuning,DEEP,tuning[NEBULA_SCALE]);color(out+96,tuning,GLOW,tuning[NEBULA_SPEED]);
    }
    public static int samples(int quality){return quality<=0?25:quality==1?16:10;}
    private static void color(long at,float[] tuning,int red,float extra) {
        put(at,Math.pow(tuning[red],2.2),Math.pow(tuning[red+1],2.2),Math.pow(tuning[red+2],2.2),extra);
    }
    private static void put(long at,double x,double y,double z,double w) {
        MemoryUtil.memPutFloat(at,(float)x);MemoryUtil.memPutFloat(at+4,(float)y);MemoryUtil.memPutFloat(at+8,(float)z);MemoryUtil.memPutFloat(at+12,(float)w);
    }
}
