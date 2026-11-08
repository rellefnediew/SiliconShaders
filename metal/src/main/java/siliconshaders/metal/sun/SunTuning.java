package siliconshaders.metal.sun;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Every tunable value of the native lighting, hand light, sky, fog and water. One table drives the
 * menu (settings.GraphicsCatalog) and config/siliconshaders-sun-tuning.json. The first LIGHTING_COUNT values map
 * to SunLighting.p; the final water values use the separate WaterFrame packet.
 */
public final class SunTuning {
    public enum Group { LIGHT, HAND, SKY, WATER, MATERIAL }
    /** A value with its menu range; step 1 with range 0-1 shows as a switch. */
    public record Param(String key,Group group,float neutral,float min,float max,float step) {
        public boolean toggle() { return step==1&&min==0&&max==1; }
        public float clamp(float value) { return Math.max(min,Math.min(max,Math.round(value/step)*step)); }
    }
    private static Param p(String key,Group group,float neutral,float min,float max,float step) { return new Param(key,group,neutral,min,max,step); }
    private static final float C=0.01f;  // color channel step
    // Defaults follow Complementary's look: golden noon sun, deep orange sunset, sky-tinted ambient,
    // faint violet-blue nights dimmed by the moon phase, warm orange block light.
    public static final List<Param> PARAMS=List.of(
        // p[0] noon sun, p[1] sunset sun (color, intensity)
        p("sunNoonR",Group.LIGHT,1.00f,0,1,C),p("sunNoonG",Group.LIGHT,0.85f,0,1,C),p("sunNoonB",Group.LIGHT,0.58f,0,1,C),p("sunNoon",Group.LIGHT,2.40f,0,6,0.05f),
        p("sunSetR",Group.LIGHT,1.00f,0,1,C),p("sunSetG",Group.LIGHT,0.55f,0,1,C),p("sunSetB",Group.LIGHT,0.28f,0,1,C),p("sunSet",Group.LIGHT,2.20f,0,6,0.05f),
        // p[2] day sky light, p[3] dusk sky light, p[4] night sky light
        p("skyDayR",Group.LIGHT,0.50f,0,1,C),p("skyDayG",Group.LIGHT,0.66f,0,1,C),p("skyDayB",Group.LIGHT,1.00f,0,1,C),p("skyDay",Group.LIGHT,0.75f,0,3,0.05f),
        p("skyDuskR",Group.LIGHT,0.64f,0,1,C),p("skyDuskG",Group.LIGHT,0.52f,0,1,C),p("skyDuskB",Group.LIGHT,0.62f,0,1,C),p("skyDusk",Group.LIGHT,0.55f,0,3,0.05f),
        p("skyNightR",Group.LIGHT,0.46f,0,1,C),p("skyNightG",Group.LIGHT,0.52f,0,1,C),p("skyNightB",Group.LIGHT,0.85f,0,1,C),p("skyNight",Group.LIGHT,0.18f,0,1,0.01f),
        // p[5] block light (color, intensity)
        p("blockR",Group.LIGHT,1.00f,0,1,C),p("blockG",Group.LIGHT,0.60f,0,1,C),p("blockB",Group.LIGHT,0.38f,0,1,C),p("block",Group.LIGHT,1.00f,0,3,0.05f),
        // p[6] minimum light, block light damping in daylight, shadow brightness, full-moon brightness
        p("minimum",Group.LIGHT,0.020f,0,0.2f,0.005f),p("blockDay",Group.LIGHT,0.45f,0,1,0.05f),p("shadowFill",Group.LIGHT,1.00f,0,2,0.05f),p("moonFull",Group.LIGHT,1.00f,0,1.5f,0.05f),
        // p[7] partial and new moon brightness, rain dimming, spare
        p("moonPartial",Group.LIGHT,0.85f,0,1.5f,0.05f),p("moonNew",Group.LIGHT,0.60f,0,1.5f,0.05f),p("rainDim",Group.LIGHT,0.45f,0,1,0.05f),p("lightSpare",Group.LIGHT,0,0,0,1),
        // p[8] hand light: switch, intensity, range scale, colored items; p[9] spares
        p("handLight",Group.HAND,1,0,1,1),p("handIntensity",Group.HAND,1.0f,0,3,0.05f),p("handRange",Group.HAND,1.0f,0.25f,3,0.05f),p("handColors",Group.HAND,1,0,1,1),
        p("handSpare0",Group.HAND,0,0,0,1),p("handSpare1",Group.HAND,0,0,0,1),p("handSpare2",Group.HAND,0,0,0,1),p("handSpare3",Group.HAND,0,0,0,1),
        // p[10] day zenith, p[11] day horizon, p[12] night zenith, p[13] night horizon (color, intensity)
        p("skyUpR",Group.SKY,0.22f,0,1,C),p("skyUpG",Group.SKY,0.44f,0,1,C),p("skyUpB",Group.SKY,0.96f,0,1,C),p("skyUp",Group.SKY,1.00f,0,3,0.05f),
        p("horizonR",Group.SKY,0.64f,0,1,C),p("horizonG",Group.SKY,0.80f,0,1,C),p("horizonB",Group.SKY,1.00f,0,1,C),p("horizon",Group.SKY,1.00f,0,3,0.05f),
        p("nightUpR",Group.SKY,0.16f,0,1,C),p("nightUpG",Group.SKY,0.22f,0,1,C),p("nightUpB",Group.SKY,0.50f,0,1,C),p("nightUp",Group.SKY,0.06f,0,1,0.01f),
        p("nightHorizonR",Group.SKY,0.30f,0,1,C),p("nightHorizonG",Group.SKY,0.38f,0,1,C),p("nightHorizonB",Group.SKY,0.62f,0,1,C),p("nightHorizon",Group.SKY,0.10f,0,1,0.01f),
        // p[14] sunset band (color, strength); p[15] sun glare, moon glare, own sky strength, vanilla sun/moon/stars
        p("sunsetR",Group.SKY,1.00f,0,1,C),p("sunsetG",Group.SKY,0.46f,0,1,C),p("sunsetB",Group.SKY,0.22f,0,1,C),p("sunset",Group.SKY,1.00f,0,3,0.05f),
        p("sunGlare",Group.SKY,1.00f,0,3,0.05f),p("moonGlare",Group.SKY,0.50f,0,3,0.05f),p("skyStrength",Group.SKY,1.00f,0,1,0.05f),p("skyFeatures",Group.SKY,1.00f,0,2,0.05f),
        // p[16] atmospheric fog: density, height falloff, strength, own sky and fog switch
        p("fogDensity",Group.SKY,0.0030f,0,0.03f,0.0005f),p("fogHeight",Group.SKY,0.020f,0,0.1f,0.005f),p("fogStrength",Group.SKY,1.00f,0,1,0.05f),p("skyEnabled",Group.SKY,1,0,1,1),
        // p[17] moonlight (color, intensity), p[18] moon shadows switch and spares
        // Complementary-like faint violet-blue moonlight, about 6% of the noon sun.
        p("moonLightR",Group.LIGHT,0.75f,0,1,C),p("moonLightG",Group.LIGHT,0.70f,0,1,C),p("moonLightB",Group.LIGHT,1.00f,0,1,C),p("moonLight",Group.LIGHT,0.18f,0,2,0.01f),
        p("moonShadows",Group.LIGHT,1,0,1,1),p("moonSpare0",Group.LIGHT,0,0,0,1),p("moonSpare1",Group.LIGHT,0,0,0,1),p("moonSpare2",Group.LIGHT,0,0,0,1),
        // WaterFrame only: these values are not appended to SunLighting.p.
        p("waterAmplitude",Group.WATER,1,0,4,0.05f),p("waterSpeed",Group.WATER,1,0,3,0.05f),
        p("waterSize",Group.WATER,1,0.25f,4,0.05f),p("waterReflection",Group.WATER,1,0,2,0.05f),
        p("waterRefraction",Group.WATER,1,0,2,0.05f),p("waterDensity",Group.WATER,1,0,3,0.05f),
        p("waterVisibility",Group.WATER,64,16,192,1),p("waterCaustics",Group.WATER,1,0,3,0.05f),
        p("waterDistortion",Group.WATER,1,0,3,0.05f),p("waterHighlights",Group.WATER,1,0,3,0.05f),
        // waterFastTrace: refraction without per-step matrices, same result (WaterFrame effects.z).
        // waterEdgeFade: reflections fade out over this share of the screen toward its edges (WaterFrame camera.w).
        p("waterFastTrace",Group.WATER,1,0,1,1),p("waterEdgeFade",Group.WATER,0.08f,0,0.25f,0.01f),
        // waterSteps: the reflection trace's step cap (WaterFrame controls.y); fewer is faster, but thin objects are missed sooner.
        p("waterSteps",Group.WATER,32,8,48,1),
        // Performance choices, 0 = the full-quality behaviour. waterReflectionQuality thins the reflection search
        // (WaterFrame quality.x), underwaterQuality simplifies the underwater pass (quality.y): 0 high, 1 medium, 2 low.
        p("waterReflectionQuality",Group.WATER,0,0,2,1),p("underwaterQuality",Group.WATER,0,0,2,1),
        // shadowHalfResolution: the shadow visibility pass evaluates one pixel per 2x2 block (native, not SunLighting.p).
        p("shadowHalfResolution",Group.LIGHT,0,0,1,1),
        // renderScale: the world renders at 100/85/67/50 % per axis and MetalFX enlarges it before hand and HUD (RenderScale).
        p("renderScale",Group.LIGHT,0,0,3,1),
        // The sun.18.11 shadow fixes, on by default: receiver-plane comparison against grazing-sun flicker, and bounded
        // offsets without raster slope bias against light leaking past one-block roofs and walls (native flags).
        p("shadowFlickerFix",Group.LIGHT,1,0,1,1),p("shadowContactFix",Group.LIGHT,1,0,1,1),
        // SSAO has its own packet. Appended so existing lighting/water offsets stay stable.
        p("ssao",Group.LIGHT,1,0,1,1),p("ssaoStrength",Group.LIGHT,0.65f,0,1.5f,0.05f),
        p("ssaoRadius",Group.LIGHT,1.25f,0.25f,3,0.05f),p("ssaoQuality",Group.LIGHT,1,0,2,1),
        // Rain and thunder (SunWeather): computed per frame on the CPU and handed to the shaders in spare packet slots.
        p("weather",Group.SKY,1,0,1,1),p("weatherSun",Group.SKY,0.38f,0,1,0.01f),p("weatherMoon",Group.SKY,0.55f,0,1,0.01f),p("weatherThunder",Group.SKY,0.6f,0,1,0.05f),
        p("weatherFog",Group.SKY,0.75f,0,3,0.05f),p("weatherFlash",Group.SKY,1,0,2,0.05f),p("weatherDrops",Group.SKY,1,0,1,1),
        p("dropOpacity",Group.SKY,0.2f,0,1,0.01f),p("dropBrightness",Group.SKY,1,0,1.35f,0.05f),
        p("stormDropOpacity",Group.SKY,0.3f,0,1,0.01f),p("stormDropBrightness",Group.SKY,0.7f,0,1.35f,0.05f),
        p("rainSplashes",Group.SKY,0,0,1,1),
        p("rainWind",Group.SKY,1,0,1,1),p("rainAngle",Group.SKY,12,0,45,1),p("rainDirection",Group.SKY,90,0,360,5),
        p("rainGusts",Group.SKY,0.3f,0,1,0.05f),p("rainWindSpeed",Group.SKY,1,0,3,0.05f),
        p("dropWidth",Group.SKY,0.65f,0.25f,2,0.05f),p("dropLength",Group.SKY,0.8f,0.25f,2,0.05f),p("dropVariation",Group.SKY,0.3f,0,0.75f,0.05f),
        p("weatherWater",Group.SKY,1,0,2,0.05f),
        // Wet surfaces in rain (SunWeather.Wetness): switch, strength, puddle coverage.
        p("wetSurfaces",Group.SKY,1,0,1,1),p("wetStrength",Group.SKY,1,0,2,0.05f),p("wetPuddles",Group.SKY,0.5f,0,1,0.05f),
        p("wetReflectionRadius",Group.SKY,32,0,128,8),
        // How long the world stays wet after rain (wetness half life while drying, seconds): puddles and rainbows last with it.
        p("wetDrying",Group.SKY,120,10,600,10),
        // Atmosphere (SunAtmosphere): aurora conditions 0 off .. 3 every clear night, strengths, rainbows after rain.
        p("aurora",Group.SKY,1,0,3,1),p("auroraStrength",Group.SKY,1,0,2,0.05f),p("auroraBlocky",Group.SKY,0,0,1,1),p("auroraBlur",Group.SKY,0.3f,0,1,0.05f),p("auroraDepth",Group.SKY,1,0.5f,2,0.05f),p("nightNebula",Group.SKY,1,0,2,0.05f),
        p("auroraDirection",Group.SKY,0,0,360,5),p("auroraElevation",Group.SKY,25,10,65,1),
        p("auroraSpeed",Group.SKY,2,0,5,0.1f),p("auroraQuality",Group.SKY,1,0,2,1),p("auroraCount",Group.SKY,6,1,10,1),
        p("auroraCurved",Group.SKY,1,0,1,1),p("auroraCurvature",Group.SKY,1,0,2,0.05f),p("auroraCurveExtent",Group.SKY,45,20,90,5),
        // Rainbows: 0 off, 1 after rain, 2 whenever the sun is low and it does not rain; strength and size (diameter).
        p("rainbows",Group.SKY,1,0,2,1),p("rainbowStrength",Group.SKY,1,0,2,0.05f),p("rainbowSize",Group.SKY,1,0.5f,2,0.05f),
        // Independent live material packet, not part of the original 76 lighting values.
        p("materials",Group.MATERIAL,1,0,1,1),p("materialGloss",Group.MATERIAL,1,0,3,0.05f),
        p("materialRoughness",Group.MATERIAL,1,0.5f,2,0.05f),p("materialEmission",Group.MATERIAL,1,0,3,0.05f),
        p("materialTransmission",Group.MATERIAL,1,0,2,0.05f),p("materialMetal",Group.MATERIAL,1,0,1,1),
        p("materialStone",Group.MATERIAL,1,0,1,1),p("materialWood",Group.MATERIAL,1,0,1,1),
        p("materialPlants",Group.MATERIAL,1,0,1,1),p("materialGlass",Group.MATERIAL,1,0,1,1),
        p("materialOther",Group.MATERIAL,1,0,1,1),p("materialOreGlow",Group.MATERIAL,0,0,1,1),
        p("materialTransparent",Group.MATERIAL,1,0,1,1),p("materialOpacity",Group.MATERIAL,1,0,2,0.05f),
        p("materialReflections",Group.MATERIAL,1,0,2,0.05f),
        p("materialDetail",Group.MATERIAL,1,0,1,1),p("materialDetailStrength",Group.MATERIAL,1,0,2,0.05f),
        p("materialOpaqueReflections",Group.MATERIAL,1,0,1,1),p("materialOpaqueReflectionStrength",Group.MATERIAL,1,0,2,0.05f),
        p("materialWorldReflections",Group.MATERIAL,1,0,1,1),p("materialWorldReflectionStrength",Group.MATERIAL,1,0,2,0.05f),
        p("materialWorldReflectionQuality",Group.MATERIAL,1,0,2,1),p("materialWorldReflectionDistance",Group.MATERIAL,64,16,128,8),
        p("materialWorldReflectionSteps",Group.MATERIAL,32,8,48,1),p("materialWorldReflectionResolution",Group.MATERIAL,1,0,2,1),
        // Colored blocklight (BlockLights): switch, and the cube of chunks/sections around the camera that holds the field.
        p("coloredLight",Group.LIGHT,1,0,1,1),p("coloredLightRange",Group.LIGHT,5,2,14,1),
        // Sun and moon (SunCelestial): 0 vanilla textures, 1 custom bodies. Sizes are angular radii in degrees.
        p("celestialStyle",Group.SKY,0,0,1,1),
        p("celestialSunSize",Group.SKY,3,0.5f,10,0.1f),p("celestialSunR",Group.SKY,1.00f,0,1,C),p("celestialSunG",Group.SKY,0.90f,0,1,C),p("celestialSunB",Group.SKY,0.75f,0,1,C),
        p("celestialSunIntensity",Group.SKY,16,0,50,0.5f),p("celestialSunGlow",Group.SKY,1,0,2,0.05f),
        p("celestialMoonSize",Group.SKY,2.5f,0.5f,10,0.1f),p("celestialMoonR",Group.SKY,0.85f,0,1,C),p("celestialMoonG",Group.SKY,0.90f,0,1,C),p("celestialMoonB",Group.SKY,1.00f,0,1,C),
        p("celestialMoonIntensity",Group.SKY,2,0,10,0.1f),p("celestialMoonGlow",Group.SKY,0.5f,0,2,0.05f),
        p("celestialMoonPhases",Group.SKY,1,0,1,1),p("celestialMoonSurface",Group.SKY,0.6f,0,1,0.05f),
        p("celestialHorizon",Group.SKY,1,0,1,1),p("celestialRain",Group.SKY,0.2f,0,1,0.05f),
        p("celestialSunShape",Group.SKY,0,0,2,1),p("celestialMoonShape",Group.SKY,0,0,2,1),
        p("celestialMoonPhaseAngle",Group.SKY,0,0,360,1),p("celestialMoonPhaseOffset",Group.SKY,0,0,360,1),
        p("celestialMoonPhaseSoftness",Group.SKY,0.25f,0,1,0.01f),
        // Aurora and nebula appearance (SunAurora, the sky packet): the curtain's and the crown's color; the nebula's two colors, size and drift.
        p("auroraCurtainR",Group.SKY,0.64f,0,1,C),p("auroraCurtainG",Group.SKY,1.00f,0,1,C),p("auroraCurtainB",Group.SKY,0.88f,0,1,C),
        p("auroraCrownR",Group.SKY,0.78f,0,1,C),p("auroraCrownG",Group.SKY,0.46f,0,1,C),p("auroraCrownB",Group.SKY,1.00f,0,1,C),
        p("nebulaDeepR",Group.SKY,0.12f,0,1,C),p("nebulaDeepG",Group.SKY,0.24f,0,1,C),p("nebulaDeepB",Group.SKY,0.62f,0,1,C),
        p("nebulaGlowR",Group.SKY,0.60f,0,1,C),p("nebulaGlowG",Group.SKY,0.44f,0,1,C),p("nebulaGlowB",Group.SKY,0.78f,0,1,C),
        p("nebulaScale",Group.SKY,1,0.25f,3,0.05f),p("nebulaSpeed",Group.SKY,1,0,4,0.05f),
        // The End sky's two bodies (SunDimension), each with its own switch.
        p("endBlackHole",Group.SKY,1,0,1,1),p("endPlanet",Group.SKY,0,0,1,1),
        p("endRingTransparency",Group.SKY,0.06f,0,1,0.01f),
        // The black hole's place, light and look, and the stars (SunDimension).
        p("endLightElevation",Group.SKY,24,5,85,1),p("endLightStrength",Group.SKY,1,0,2,0.05f),p("endHoleSize",Group.SKY,1,0.5f,2.5f,0.05f),
        p("endDiscBrightness",Group.SKY,1,0,3,0.05f),p("endStars",Group.SKY,1,0,3,0.05f),
        // End atmosphere: evaluated in the shared fog functions, not the 76-value lighting prefix.
        p("endFog",Group.SKY,1,0,1,1),p("endFogStrength",Group.SKY,1,0,2,0.05f),p("endFogDistance",Group.SKY,128,32,512,8),
        // Its look: uniforms only, so none of these changes what a frame costs (wisps at 0 even saves their sines).
        p("endFogBrightness",Group.SKY,1,0,3,0.05f),p("endFogColorR",Group.SKY,0.30f,0,1,C),p("endFogColorG",Group.SKY,0.21f,0,1,C),p("endFogColorB",Group.SKY,0.47f,0,1,C),
        p("endFogHeight",Group.SKY,68,-64,320,4),p("endFogThickness",Group.SKY,1,0.25f,3,0.05f),p("endFogSize",Group.SKY,1,0.25f,4,0.05f),
        p("endFogWisps",Group.SKY,0.30f,0,1,0.05f),p("endFogSpeed",Group.SKY,1,0,5,0.1f),
        p("endFogEmber",Group.SKY,1,0,3,0.05f),p("endFogEmberColorR",Group.SKY,0.54f,0,1,C),p("endFogEmberColorG",Group.SKY,0.30f,0,1,C),p("endFogEmberColorB",Group.SKY,0.12f,0,1,C),
        p("endFogHoleLight",Group.SKY,1,0,2,0.05f),
        // Underwater fog and caustics at half size (WaterFrame quality.w); off is the original full-size pass.
        p("underwaterHalf",Group.WATER,1,0,1,1),
        // The End's stars in the Overworld's clear night (SunAtmosphere); 0 brings vanilla's stars back.
        p("nightStars",Group.SKY,1,0,3,0.05f),
        // Separate night weather controls, folded on the CPU into the existing lighting packet. Append to keep all offsets stable.
        p("weatherNightDim",Group.SKY,0.25f,0,1,0.05f),p("weatherThunderMoon",Group.SKY,0.80f,0,1,0.05f));
    public static final int COUNT=PARAMS.size(),LIGHTING_COUNT=76,WATER_START=76;
    public static final int MATERIAL_START=index("materials"),MATERIAL_COUNT=25;
    public static final int WATER_REFLECTION_QUALITY=index("waterReflectionQuality"),UNDERWATER_QUALITY=index("underwaterQuality"),UNDERWATER_HALF=index("underwaterHalf"),
        SHADOW_HALF_RESOLUTION=index("shadowHalfResolution"),RENDER_SCALE=index("renderScale"),
        SHADOW_FLICKER_FIX=index("shadowFlickerFix"),SHADOW_CONTACT_FIX=index("shadowContactFix");
    public static final int HAND_LIGHT=index("handLight"),HAND_INTENSITY=index("handIntensity"),HAND_RANGE=index("handRange"),HAND_COLORS=index("handColors");
    public static final int MOON_FULL=index("moonFull"),MOON_PARTIAL=index("moonPartial"),MOON_NEW=index("moonNew"),SKY_ENABLED=index("skyEnabled"),MOON_LIGHT=index("moonLight"),MOON_SHADOWS=index("moonShadows");

    /**
     * Night brightness by moon phase (0 full .. 4 new .. 7), blended smoothly between new, partial and full moon
     * like Complementary's moon phase influence: half-way phases sit on the partial value.
     */
    public static float moonPhase(float[] values,int phase) {
        float factor=Math.abs(phase-4)*0.25f;
        float dark=values[MOON_NEW],partial=values[MOON_PARTIAL],full=values[MOON_FULL];
        return factor<0.5f?dark+(partial-dark)*smooth(factor/0.5f):partial+(full-partial)*smooth((factor-0.5f)/0.5f);
    }
    private static float smooth(float x) { x=Math.max(0,Math.min(1,x));return x*x*(3-2*x); }
    private static final Gson JSON=new Gson();
    private static class Holder { static final SunTuning VALUE=new SunTuning(FabricLoader.getInstance().getGameDir()); }
    public static SunTuning get() { return Holder.VALUE; }
    private final Path file;
    private final float[] values=new float[COUNT];
    public SunTuning(Path directory) {
        siliconshaders.metal.BrandMigration.settings(directory);
        file=directory.resolve("config/siliconshaders-sun-tuning.json");
        for(int i=0;i<COUNT;i++)values[i]=PARAMS.get(i).neutral();
        try {
            if(Files.isRegularFile(file)) {
                if(Files.size(file)>16384)throw new IllegalArgumentException("Oversized sun tuning");
                JsonObject object=JSON.fromJson(Files.readString(file),JsonObject.class);
                // materials.3 tied resolution to quality and used a fixed 48-step budget. Preserve existing choices on migration.
                if(object!=null&&object.has("materialWorldReflectionQuality")) {
                    if(!object.has("materialWorldReflectionSteps"))values[index("materialWorldReflectionSteps")]=48;
                    if(!object.has("materialWorldReflectionResolution")) {
                        float old=object.get("materialWorldReflectionQuality").getAsFloat();
                        values[index("materialWorldReflectionResolution")]=Float.isFinite(old)&&Math.round(PARAMS.get(index("materialWorldReflectionQuality")).clamp(old))==0?0:1;
                    }
                }
                for(int i=0;i<COUNT;i++) {
                    Param param=PARAMS.get(i);
                    if(object!=null&&object.has(param.key())) {
                        float value=object.get(param.key()).getAsFloat();
                        if(Float.isFinite(value))values[i]=param.clamp(value);
                    }
                }
            }
        } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders sun] Tuning settings ignored: "+failure.getMessage()); }
    }
    public synchronized float value(int index) { return values[index]; }
    /** A copy in table order (the shader packet). */
    public synchronized float[] values() { return values.clone(); }
    /** Render-thread scratch storage: retain snapshot isolation without allocating a new array each frame. */
    public synchronized void copyValues(float[] target) {
        if(target.length!=COUNT)throw new IllegalArgumentException("Invalid tuning snapshot size");
        System.arraycopy(values,0,target,0,COUNT);
    }
    /** Live menu session: validate the whole snapshot before publishing, without disk writes. */
    public synchronized void preview(float[] next) {
        if(next==null||next.length!=COUNT)throw new IllegalArgumentException("Invalid lighting settings");
        float[] checked=next.clone();for(int i=0;i<COUNT;i++){if(!Float.isFinite(checked[i]))throw new IllegalArgumentException("Invalid lighting value");checked[i]=PARAMS.get(i).clamp(checked[i]);}
        System.arraycopy(checked,0,values,0,COUNT);
    }
    /** Benchmark only (bench.ShaderBench): replaces every value for this session without writing the tuning file. */
    public synchronized void benchmark(float[] next) { for(int i=0;i<COUNT;i++)values[i]=PARAMS.get(i).clamp(next[i]); }
    /** The table index of a value's key, or -1. */
    public static int index(String key) { for(int i=0;i<COUNT;i++)if(PARAMS.get(i).key().equals(key))return i;return -1; }
    public synchronized void set(int index,float value) { values[index]=PARAMS.get(index).clamp(value);save(); }
    public synchronized void reset(Group group) { for(int i=0;i<COUNT;i++)if(PARAMS.get(i).group()==group)values[i]=PARAMS.get(i).neutral();save(); }
    private void save() {
        JsonObject object=new JsonObject();
        for(int i=0;i<COUNT;i++)if(PARAMS.get(i).max()>PARAMS.get(i).min())object.addProperty(PARAMS.get(i).key(),values[i]);
        try {
            Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"sun-tuning-",".tmp");
            try { Files.writeString(temporary,JSON.toJson(object)+"\n");Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(temporary); }
        } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders sun] Tuning settings not saved: "+failure.getMessage()); }
    }
}
