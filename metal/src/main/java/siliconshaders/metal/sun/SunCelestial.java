package siliconshaders.metal.sun;

import org.lwjgl.system.MemoryUtil;

/**
 * Custom sun and moon (sun.celestialStyle 1), drawn by the native sky (sun.metal sunCelestialBodies) while vanilla's
 * sun and moon quads are skipped (SunCelestialMixin). Prefix: sun and moon radiance/radius;
 * glows / orbital phase (negative: always full) / surface; horizon / rain / shapes; phase rotation cosine/sine / softness.
 */
public final class SunCelestial {
    public static final int STYLE=SunTuning.index("celestialStyle"),SUN_SIZE=SunTuning.index("celestialSunSize"),SUN_R=SunTuning.index("celestialSunR"),
        SUN_INTENSITY=SunTuning.index("celestialSunIntensity"),SUN_GLOW=SunTuning.index("celestialSunGlow"),MOON_SIZE=SunTuning.index("celestialMoonSize"),
        MOON_R=SunTuning.index("celestialMoonR"),MOON_INTENSITY=SunTuning.index("celestialMoonIntensity"),MOON_GLOW=SunTuning.index("celestialMoonGlow"),
        MOON_PHASES=SunTuning.index("celestialMoonPhases"),MOON_SURFACE=SunTuning.index("celestialMoonSurface"),HORIZON=SunTuning.index("celestialHorizon"),
        RAIN=SunTuning.index("celestialRain"),SKY=SunTuning.index("skyEnabled"),SUN_SHAPE=SunTuning.index("celestialSunShape"),
        MOON_SHAPE=SunTuning.index("celestialMoonShape"),PHASE_ANGLE=SunTuning.index("celestialMoonPhaseAngle"),
        PHASE_OFFSET=SunTuning.index("celestialMoonPhaseOffset"),PHASE_SOFTNESS=SunTuning.index("celestialMoonPhaseSoftness"),FLOATS=20+MoonEmission.FLOATS;
    /** The shipped brightnesses. The shader's fringe is radiance times glow, so glow is packed relative to these: same picture at the defaults, independent of brightness. */
    private static final float SUN_NEUTRAL=SunTuning.PARAMS.get(SUN_INTENSITY).neutral(),MOON_NEUTRAL=SunTuning.PARAMS.get(MOON_INTENSITY).neutral();
    private SunCelestial(){}
    /** Keep resource-pack textures whenever the native lighting/sky path is unavailable or in a diagnostic view. */
    public static boolean active(){
        var settings=SunSettings.get().state();
        if(!settings.enabled()||!settings.gbuffer()||settings.gbufferView()!=0||siliconshaders.metal.own.OwnTerrain.get()==null)return false;
        var tuning=SunTuning.get();return tuning.value(STYLE)>.5f&&tuning.value(SKY)>.5f;
    }
    /** moonPhase: vanilla's phase index, 0 full moon to 4 new moon and back. */
    public static void write(long out,float[] tuning,int moonPhase) {
        body(out,tuning,SUN_R,SUN_INTENSITY,SUN_SIZE);body(out+16,tuning,MOON_R,MOON_INTENSITY,MOON_SIZE);
        double degrees=(Math.floorMod(moonPhase,8)*45.0+tuning[PHASE_OFFSET])%360;
        float phase=tuning[MOON_PHASES]>.5f?(float)Math.toRadians(degrees):-1;
        put(out+32,glow(tuning[SUN_GLOW],tuning[SUN_INTENSITY],SUN_NEUTRAL),glow(tuning[MOON_GLOW],tuning[MOON_INTENSITY],MOON_NEUTRAL),phase,tuning[MOON_SURFACE]);
        put(out+48,tuning[HORIZON],tuning[RAIN],tuning[STYLE]>.5f?tuning[SUN_SHAPE]+1:0,tuning[MOON_SHAPE]);
        double rotation=Math.toRadians(tuning[PHASE_ANGLE]);
        put(out+64,(float)Math.cos(rotation),(float)Math.sin(rotation),tuning[PHASE_SOFTNESS],moonGlareColor(tuning));
        MoonEmission.write(out+80,phase,Math.round(tuning[MOON_SHAPE]),(float)Math.cos(rotation),(float)Math.sin(rotation),tuning[PHASE_SOFTNESS]);
    }
    private static float glow(float glow,float intensity,float neutral){return intensity>0?glow*neutral/intensity:0;}
    /** How the moon's color scales the sky's glow around it, at the shipped brightness; 0 hides the glow with the moon. */
    private static float moonGlareColor(float[] tuning){
        if(tuning[MOON_INTENSITY]<=0)return 0;
        float peak=(float)Math.max(Math.pow(tuning[MOON_R],2.2),Math.max(Math.pow(tuning[MOON_R+1],2.2),Math.pow(tuning[MOON_R+2],2.2)));
        return Math.clamp(peak*MOON_NEUTRAL*.5f,0,5);
    }
    private static void body(long at,float[] tuning,int red,int intensity,int size){
        float strength=tuning[intensity];
        put(at,(float)Math.pow(tuning[red],2.2)*strength,(float)Math.pow(tuning[red+1],2.2)*strength,
            (float)Math.pow(tuning[red+2],2.2)*strength,(float)Math.toRadians(tuning[size]));
    }
    private static void put(long at,float x,float y,float z,float w) {
        MemoryUtil.memPutFloat(at,x);MemoryUtil.memPutFloat(at+4,y);MemoryUtil.memPutFloat(at+8,z);MemoryUtil.memPutFloat(at+12,w);
    }
}
