package siliconshaders.metal.sun;

import org.lwjgl.system.MemoryUtil;

/** Cached camera-column height and skylight, independent of light/shadow on any receiving surface. */
public final class SunFog {
    private SunFog() {}
    /** The camera's height above sea level, for the haze's density (sun.metal sunHaze); into the aurora packet's free slot. */
    public static final int HEIGHT_SLOT=11;
    public static float height(double cameraY,int seaLevel) { float h=(float)(cameraY-seaLevel);return Float.isFinite(h)?Math.max(0,h):0; }
    public static float exposure(int skyLight) {
        float x=Math.clamp((skyLight-2)/6f,0,1);
        return x*x*(3-2*x);
    }
    public static float exposure(int skyLight,boolean openAir) { return openAir?1:exposure(skyLight); }
    public static void write(long tuning,int skyLight) { write(tuning,skyLight,false); }
    public static void write(long tuning,int skyLight,boolean openAir) {
        long slot=tuning+4L*SunTuning.SKY_ENABLED;
        if(MemoryUtil.memGetFloat(slot)>.5f)MemoryUtil.memPutFloat(slot,2+exposure(skyLight,openAir));
    }
}
