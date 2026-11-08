package siliconshaders.metal.sun;

/**
 * Rain and thunder for the native lighting, as one cloud layer between the sky and the scene. The layer's cover closes ahead of
 * the rain (the sky is grey before the rain is heavy), and light passes it by its optical depth: each setting is the transmittance
 * at full cover, so a frame's share is setting^cover, never a straight fade. A thunderhead is a second layer on top, which
 * multiplies, with separate sun/day and moon/night transmission and rain dimming. A disc seen through the layer loses more than the light it casts, whose scattered part still arrives: its share is
 * the square. Haze thickens with the cover and more in a storm, but stays low, as the rain washes it out of the higher air. A
 * lightning strike flashes the sky over the game's own sky flash.
 * Everything here is computed once per frame on the CPU and folded into the lighting packet the shaders already read: the
 * haze density and height falloff, the sky light intensities, and two spare slots (SunTuning's lightSpare and moonSpare0,
 * never saved) for the sky's thunder darkening and the flash. The GPU does no extra pass, sample or branch for weather. The game's own rain dimming (SkyRenderState.rainBrightness, the sun texture's alpha) must not scale the
 * light: at full rain it is 0, which switched off sun, shadows and everything built on them.
 */
public final class SunWeather {
    public static final int WEATHER=SunTuning.index("weather"),SUN=SunTuning.index("weatherSun"),MOON=SunTuning.index("weatherMoon"),THUNDER=SunTuning.index("weatherThunder"),
        FOG=SunTuning.index("weatherFog"),FLASH=SunTuning.index("weatherFlash"),WATER=SunTuning.index("weatherWater"),
        NIGHT_DIM=SunTuning.index("weatherNightDim"),THUNDER_MOON=SunTuning.index("weatherThunderMoon"),RAIN_DIM=SunTuning.index("rainDim"),
        WET=SunTuning.index("wetSurfaces"),WET_STRENGTH=SunTuning.index("wetStrength"),WET_PUDDLES=SunTuning.index("wetPuddles"),WET_DRYING=SunTuning.index("wetDrying"),WET_REFLECTION_RADIUS=SunTuning.index("wetReflectionRadius");
    /** The packet slots: p[7].w the sky's thunder darkening, p[18].y the flash (SunTuning lightSpare, moonSpare0). */
    public static final int THUNDER_SLOT=SunTuning.index("lightSpare"),FLASH_SLOT=SunTuning.index("moonSpare0");
    /** Wet surfaces: p[18].z wetness times strength (0: dry, the shader skips all of it), p[18].w puddle coverage. */
    public static final int WET_SLOT=SunTuning.index("moonSpare1"),PUDDLE_SLOT=SunTuning.index("moonSpare2");
    /** Folded into: the haze density and height falloff (p[16].xy), the day, dusk and night sky light intensities (p[2..4].w). */
    static final int FOG_DENSITY=SunTuning.index("fogDensity"),FOG_HEIGHT=SunTuning.index("fogHeight");
    static final int[] SKY_LIGHT={SunTuning.index("skyDay"),SunTuning.index("skyDusk"),SunTuning.index("skyNight")};

    /**
     * rain 0..1; cover: the cloud layer, 0..1; darken: the share thunder takes from sky and ambient light at this time of day;
     * light scales the sun, moonLight the moon independently; flash 0..2; fog: extra haze density, times the clear one.
     */
    public record Frame(float rain,float cover,float darken,float light,float moonLight,float flash,float fog) {
        public static final Frame CLEAR=new Frame(0,0,0,1,1,0,0);
    }
    /** How much of the haze's extra density also lifts it: rain keeps it low. */
    static final float HAZE_REACH=0.4f;
    /** A storm's extra haze on top of the cover's, as a share of it. */
    static final float STORM_HAZE=0.4f;
    /** The water's color follows this share of the light the clouds take. */
    static final float WATER_SHARE=0.55f;
    private SunWeather() {}

    /**
     * This frame's weather. rain and thunder: the level's levels at the frame's partial tick; flashTicks: the level's sky flash
     * (2 on a strike, counted down per tick); partialTick: how far into the tick the frame is.
     */
    public static Frame frame(float rain,float thunder,int flashTicks,float partialTick,float[] tuning) {
        return frame(rain,thunder,flashTicks,partialTick,1,tuning);
    }
    /** sunHeight is the real sun's elevation, independent of whether moon shadows are enabled. */
    public static Frame frame(float rain,float thunder,int flashTicks,float partialTick,float sunHeight,float[] tuning) {
        if(tuning[WEATHER]<0.5f)return Frame.CLEAR;
        rain=clamp(rain);float storm=clamp(thunder)*rain,cover=cover(rain);
        float sunThrough=through(tuning[THUNDER],storm),moonThrough=through(tuning[THUNDER_MOON],storm);
        float through=sunThrough+(moonThrough-sunThrough)*night(sunHeight);
        float light=through(tuning[SUN],cover)*sunThrough,moonLight=through(tuning[MOON],cover)*moonThrough;
        // A strike is a sharp peak: square its two ticks' fade.
        float fade=flashTicks>0?clamp((flashTicks-partialTick)/2f):0;
        return new Frame(rain,cover,1-through,light,moonLight,fade*fade*tuning[FLASH],(cover+STORM_HAZE*storm)*tuning[FOG]);
    }
    /** The cloud layer closes ahead of the rain. */
    private static float cover(float rain) { return rain*(2-rain); }
    /** Matches sun.metal's smooth day/night blend, so dusk never snaps between the two settings. */
    private static float night(float sunHeight) {
        float day=clamp(((Float.isFinite(sunHeight)?sunHeight:1)+.10f)/.20f);
        return 1-day*day*(3-2*day);
    }
    /** The light a layer of this depth lets through, with through(share,1)=share. */
    private static float through(float share,float depth) { return depth<=0?1:(float)Math.pow(Math.max(0,share),depth); }
    /** How much the weather takes from the water's own color: a share of what the clouds take from the sun. 0 in clear weather. */
    public static float waterDim(Frame weather) { return WATER_SHARE*(1-clamp(weather.light())); }
    /** waterDim at the weatherWater setting's strength (0: water as in clear weather, 1: default, 2: twice as strong). */
    public static float waterDim(Frame weather,float strength) {
        return Math.max(0,Math.min(1,waterDim(weather)*(Float.isFinite(strength)?Math.max(0,strength):1)));
    }
    /**
     * The sky's sun and moon (and the camera's flares, which follow them) in rain: vanilla fades them out with the rain
     * (rainBrightness 1 - rain). Here a disc keeps the square of its light's share (weatherSun, weatherMoon), over the layer's
     * cover, blended over the same horizon ramp as the moon light.
     */
    public static float celestial(float rainBrightness,float sunAngle,boolean weather,float sunShare,float moonShare) {
        if(!weather||!Float.isFinite(rainBrightness+sunAngle))return rainBrightness;
        float rain=1-Math.max(0,Math.min(1,rainBrightness)),night=clamp((-(float)Math.cos(sunAngle)-0.03f)/0.15f);
        float kept=sunShare*sunShare+(moonShare*moonShare-sunShare*sunShare)*night;
        return through(kept,cover(rain));
    }
    /**
     * Into the copy of the tuning values that becomes the shader's SunLighting.p: what the shaders would otherwise compute per
     * pixel, the same result. Haze: fog extra density, and a part of it as reach; ambient sky light: thunder's darkening.
     */
    public static void pack(float[] tuning,Frame weather) {
        pack(tuning,weather,1);
    }
    public static void pack(float[] tuning,Frame weather,float sunHeight) {
        tuning[THUNDER_SLOT]=weather.darken();tuning[FLASH_SLOT]=weather.flash();
        tuning[FOG_DENSITY]*=1+weather.fog();tuning[FOG_HEIGHT]/=1+HAZE_REACH*weather.fog();
        for(int i:SKY_LIGHT)tuning[i]*=1-weather.darken();
        if(weather.cover()>0)tuning[RAIN_DIM]+=(tuning[NIGHT_DIM]-tuning[RAIN_DIM])*night(sunHeight);
    }
    /**
     * How wet the world is, after Iris's wetness (Complementary's rain puddles read it): it follows the rain over a 30 s half
     * life while wetting and a 10 s half life while drying. Only rain counts where the camera's biome rains (no deserts or
     * snow). Once per frame on the CPU.
     */
    public static final class Wetness {
        private long previous;private float value;
        /** Iris's half lives: 30 s wetting, 10 s drying. */
        public float update(long now,boolean paused,float rain,boolean rainyBiome) { return update(now,paused,rain,rainyBiome,10); }
        /** dryHalfLife: seconds (the wetDrying setting); longer keeps puddles and rainbows after rain. */
        public float update(long now,boolean paused,float rain,boolean rainyBiome,float dryHalfLife) {
            float dt=previous==0?0:(float)Math.max(0,Math.min(0.25,(now-previous)*1e-9));previous=now;
            float target=rainyBiome?clamp(rain):0;
            if(!paused&&dt>0) {
                float halfLife=target>value?30:Float.isFinite(dryHalfLife)?Math.max(1,dryHalfLife):10;
                value+=(target-value)*(1-(float)Math.pow(0.5,dt/halfLife));
            }
            return value;
        }
        public float value() { return value; }
        public void reset() { previous=0;value=0; }
    }
    /** Into the shader's packet copy: wetness at the set strength (0 when off), and the puddle coverage. */
    public static void packWet(float[] tuning,float wetness) {
        boolean on=tuning[WET]>0.5f&&Float.isFinite(wetness);
        tuning[WET_SLOT]=on?Math.max(0,Math.min(2,clamp(wetness)*tuning[WET_STRENGTH])):0;
        tuning[PUDDLE_SLOT]=tuning[WET_PUDDLES];
    }
    private static float clamp(float value) { return Float.isFinite(value)?Math.max(0,Math.min(1,value)):0; }
}
