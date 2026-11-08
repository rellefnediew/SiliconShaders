package siliconshaders.metal.sun;

import org.lwjgl.system.MemoryUtil;

/**
 * Night and after-rain sky effects, the aurora and the rainbow after Complementary (lib/atmospherics: auroraBorealis, rainbow):
 * when each is visible and how strongly, decided once per frame on the CPU; the sky passes draw them on sky pixels only (sun.metal
 * sunAtmosphere, sunNebula) and skip each on a uniform branch while it is 0.
 * The values go into four packet values no shader or native code reads, in the GPU's copy only: the full, partial and new
 * moon brightnesses (p[6].w, p[7].xy), which the CPU turns into the moon light itself (SunTuning.moonPhase) before the copy,
 * and the moon-shadow and colored-hand-light switches (p[18].x, p[8].w), which the CPU reads from its own values. The nebula's value carries minus the rainbow
 * diameter by day: the nebula shows only at night, the rainbow only by day.
 */
public final class SunAtmosphere {
    public static final int AURORA=SunTuning.index("aurora"),AURORA_STRENGTH=SunTuning.index("auroraStrength"),AURORA_BLOCKY=SunTuning.index("auroraBlocky"),AURORA_BLUR=SunTuning.index("auroraBlur"),AURORA_DEPTH=SunTuning.index("auroraDepth"),
        NEBULA=SunTuning.index("nightNebula"),RAINBOWS=SunTuning.index("rainbows"),RAINBOW_STRENGTH=SunTuning.index("rainbowStrength"),
        RAINBOW_SIZE=SunTuning.index("rainbowSize"),NIGHT_STARS=SunTuning.index("nightStars");
    /** sky[1].w of the aurora packet: the aurora's crown color has no use for it, and only Nether and End write it otherwise. */
    public static final int STARS_SLOT=19;
    public static final int AURORA_SLOT=SunTuning.index("moonFull"),NEBULA_SLOT=SunTuning.index("moonPartial"),RAINBOW_SLOT=SunTuning.index("moonNew"),
        CLOCK_SLOT=SunTuning.index("moonShadows"),BLUR_SLOT=SunTuning.index("handColors");
    /** The sky clock wraps after this many game seconds (about 4.5 hours), keeping float precision for the drifts. */
    public static final float CLOCK_PERIOD=16384;

    /**
     * aurora: visibility times strength, negative for Complementary's blocky style 1 (positive: its smooth style 2); nebula:
     * 0..2 visibility times strength; rainbow: 0..1.7 (Complementary's rainbowTime times strength); diameter; stars: visibility
     * times strength. Thunder needs rain in vanilla, so clear already covers it.
     */
    public record Frame(float aurora,float nebula,float rainbow,float diameter,float stars) {
        public static final Frame NONE=new Frame(0,0,0,1,0);
    }
    private static final int SKY_STRENGTH=SunTuning.index("skyStrength");
    private SunAtmosphere() {}

    /**
     * sunY: the sun's height (cos of its angle); rain 0..1; wetness: SunWeather.Wetness (drying after rain); the camera's
     * biome rains / snows; fullMoon: tonight's moon phase is full.
     */
    public static Frame frame(float[] tuning,float sunY,float rain,float wetness,boolean rainyBiome,boolean coldBiome,boolean fullMoon) {
        float diameter=Math.max(0.5f,Math.min(2,tuning[RAINBOW_SIZE]));
        if(!Float.isFinite(sunY+rain+wetness))return new Frame(0,0,0,diameter,0);
        rain=clamp(rain);
        float night=clamp((-sunY-0.0625f)/0.1f),clear=1-rain;
        // Aurora conditions: 1 Complementary's default (full moon in a cold biome), 2 full moon or cold biome, 3 every clear night.
        int mode=Math.round(tuning[AURORA]);
        boolean shows=mode==1?fullMoon&&coldBiome:mode==2?fullMoon||coldBiome:mode>=3;
        float aurora=(shows?night*clear*Math.max(0,tuning[AURORA_STRENGTH]):0)*(tuning[AURORA_BLOCKY]>0.5f?-1:1);
        float nebula=night*clear*Math.max(0,tuning[NEBULA]),stars=night*clear*Math.max(0,tuning[NIGHT_STARS]);
        // Rainbows (Complementary's RAINBOWS): 1 after rain, while the wet world dries in a rainy biome; 2 whenever the sun is
        // low or mid-height and it does not rain. Not at noon.
        float rainbow=0;int bows=Math.round(tuning[RAINBOWS]);
        if(bows>=1) {
            float low=clamp((sunY-0.1f)/0.15f),noon=sunY*sunY*sunY*sunY*8;
            float time=Math.max(0,Math.min(0.85f,low-Math.max(0,noon)));
            float after=bows>=2?1:rainyBiome?(float)Math.sqrt(Math.max(0,wetness-0.333f)*1.5f):0;
            rainbow=time*Math.min(1,after)*clear*Math.max(0,tuning[RAINBOW_STRENGTH]);
        }
        return new Frame(aurora,nebula,rainbow,diameter,stars);
    }
    /** Vanilla's stars leave the sky while the shader draws its own, and only then: the native sky has to be on to draw them. */
    public static boolean ownStars() {
        var settings=SunSettings.get().state();
        if(!settings.enabled()||!settings.gbuffer()||settings.gbufferView()!=0||siliconshaders.metal.own.OwnTerrain.get()==null)return false;
        var tuning=SunTuning.get();return tuning.value(NIGHT_STARS)>0&&tuning.value(SunTuning.SKY_ENABLED)>.5f&&tuning.value(SKY_STRENGTH)>0;
    }
    /** Game seconds on the sky clock (Complementary's syncedTime) from the level's game time in ticks. */
    public static float clock(long gameTime,float partialTick) {
        long period=(long)CLOCK_PERIOD*20;
        return ((gameTime%period+period)%period+partialTick)/20f;
    }
    /** Into the GPU's copy of the lighting values (after the CPU has read the moon values from its own). */
    /**
     * The aurora's depth (0.5..2, the layers' scale span: how far the curtains reach up the sky) and softness (0..1) in one
     * value: twice the depth in twentieths plus the softness. sun.metal reads the depth from its integer half, the softness
     * from the rest.
     */
    public static float packAurora(float blur,float depth) {
        float soft=Float.isFinite(blur)?Math.max(0,Math.min(1,blur)):0;
        int code=Math.round((Float.isFinite(depth)?Math.max(0.5f,Math.min(2,depth)):1)*20);
        return code*2+soft;
    }
    public static void write(long tuningAddress,Frame frame,float clock,float blur,float depth) {
        MemoryUtil.memPutFloat(tuningAddress+4L*BLUR_SLOT,packAurora(blur,depth));
        write(tuningAddress,frame,clock);
    }
    public static void write(long tuningAddress,Frame frame,float clock) {
        MemoryUtil.memPutFloat(tuningAddress+4L*AURORA_SLOT,frame.aurora());
        MemoryUtil.memPutFloat(tuningAddress+4L*NEBULA_SLOT,frame.nebula()>0?frame.nebula():-frame.diameter());
        MemoryUtil.memPutFloat(tuningAddress+4L*RAINBOW_SLOT,frame.rainbow());
        MemoryUtil.memPutFloat(tuningAddress+4L*CLOCK_SLOT,Float.isFinite(clock)?clock:0);
    }
    private static float clamp(float value) { return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0; }
}
