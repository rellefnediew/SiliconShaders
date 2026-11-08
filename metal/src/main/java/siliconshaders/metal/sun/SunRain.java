package siliconshaders.metal.sun;

import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import net.minecraft.resources.Identifier;

/**
 * Rain drops as thin silver streaks: a copy of vanilla's weather pipeline under our own name, whose fragment
 * stage the Metal backend adapts when it translates it (siliconshaders.metal.SunRainMsl). Same shaders, vertices, blending and depth
 * state as vanilla. Used while the native shader and the weatherDrops setting are on; otherwise, and for the OIT weather
 * passes, vanilla's pipeline draws. The drops' tint, brightness and opacity, separately for rain and thunderstorms, go into
 * the rain columns' vertex color on the CPU (once per column, mixed by the thunder level): no uniform, no extra per-pixel work. Wind and size variation only transform the existing UVs (RainMotion).
 */
public final class SunRain {
    public static final int DROPS=SunTuning.index("weatherDrops"),OPACITY=SunTuning.index("dropOpacity"),BRIGHTNESS=SunTuning.index("dropBrightness"),
        STORM_OPACITY=SunTuning.index("stormDropOpacity"),STORM_BRIGHTNESS=SunTuning.index("stormDropBrightness"),SPLASHES=SunTuning.index("rainSplashes");
    /** Silver with a cool edge: the drop color at brightness 1 (the lightmap still scales it in the shader). */
    static final float TINT_R=0.70f,TINT_G=0.75f,TINT_B=0.83f;
    // Set around vanilla's rain columns in WeatherEffectRenderer.prepare (render thread); snow columns stay vanilla.
    private static final RainMotion MOTION=new RainMotion();private static final RainVertices VERTICES=new RainVertices(MOTION);
    private static boolean rainColumns;private static int rgb;private static float opacity;
    public static final Identifier LOCATION=Identifier.fromNamespaceAndPath("siliconshaders","pipeline/weather");
    private static class Holder { static final RenderPipeline PIPELINE=build(siliconshaders.metal.mixin.pack.SunRainPipelinesAccess.siliconshaders$weatherSnippet()); }
    private SunRain() {}

    /** Vanilla's RenderPipelines.WEATHER under our name (the same depth state: test, no write). */
    public static RenderPipeline build(RenderPipeline.Snippet weather) {
        return RenderPipeline.builder(weather).withLocation(LOCATION)
            .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL,false)).build();
    }
    static boolean active() { return SunLighting.enabled()&&SunTuning.get().value(DROPS)>0.5f; }
    /**
     * Vanilla's splash particles where rain hits the ground (ClientLevel.tickWeatherEffects): off while the native shader is on
     * and the rainSplashes setting is off. Lava and magma keep their smoke.
     */
    public static boolean splash(Object particle) {
        return particle!=net.minecraft.core.particles.ParticleTypes.RAIN||!SunLighting.enabled()||SunTuning.get().value(SPLASHES)>0.5f;
    }
    /** The pipeline for this frame's rain and snow. */
    public static RenderPipeline pipeline(RenderPipeline vanilla) { return active()?Holder.PIPELINE:vanilla; }

    /** Before vanilla writes the rain columns: this frame's drop color and opacity, rain's mixed toward the storm's by thunder. */
    public static void beginRain(float thunder) {
        rainColumns=active();if(!rainColumns)return;
        SunTuning tuning=SunTuning.get();float t=Float.isFinite(thunder)?Math.max(0,Math.min(1,thunder)):0;
        float brightness=tuning.value(BRIGHTNESS)+(tuning.value(STORM_BRIGHTNESS)-tuning.value(BRIGHTNESS))*t;
        opacity=tuning.value(OPACITY)+(tuning.value(STORM_OPACITY)-tuning.value(OPACITY))*t;
        rgb=tint(brightness);
    }
    public static void beginRain(float thunder,long ticks,float partial,net.minecraft.world.phys.Vec3 camera) {
        beginRain(thunder);if(rainColumns)MOTION.frame(SunTuning.get().values(),ticks,partial,camera.x,camera.y,camera.z);
    }
    public static com.mojang.blaze3d.vertex.VertexConsumer vertices(com.mojang.blaze3d.vertex.VertexConsumer vanilla) {
        return rainColumns?VERTICES.bind(vanilla):vanilla;
    }
    public static void endRain() { rainColumns=false;VERTICES.clear(); }
    public static void columnPosition(float x,float z) { if(rainColumns)MOTION.column(x,z); }
    /** A column's vanilla color (white, alpha its distance fade); in rain columns our tint and opacity. */
    public static int column(int vanilla) { return rainColumns?column(vanilla,rgb,opacity):vanilla; }
    static int column(int vanilla,int rgb,float opacity) { return Math.round((vanilla>>>24)*Math.max(0,Math.min(1,opacity)))<<24|rgb; }
    /** The drop tint at a brightness, as 8-bit RGB (it saturates at 1/0.83 for the blue channel). */
    static int tint(float brightness) {
        return channel(TINT_R*brightness)<<16|channel(TINT_G*brightness)<<8|channel(TINT_B*brightness);
    }
    private static int channel(float value) { return Math.round(Math.max(0,Math.min(1,value))*255); }
}
