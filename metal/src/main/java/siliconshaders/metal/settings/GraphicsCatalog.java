package siliconshaders.metal.settings;

import java.util.*;
import siliconshaders.metal.sun.*;
import siliconshaders.metal.camera.CameraSettings;

/** Stable scalar schema shared by menus, profiles and import validation. No live Minecraft instance is required. */
public final class GraphicsCatalog {
    /**
     * group: the heading a setting sits under inside its feature (GROUP_ORDER). quality: it trades frame time for detail;
     * the quality levels set these, and picture-only profiles leave them out.
     */
    public record Rule(String key,String label,String group,float neutral,float min,float max,float step,boolean toggle,boolean basic,boolean shareable,boolean quality) {
        public float normalize(float value){if(!Float.isFinite(value)||value<min-.0001f||value>max+.0001f)throw new IllegalArgumentException("profile.range");return Math.clamp(Math.round(value/step)*step,min,max);}
    }
    private static final Set<String> BASIC=Set.of("filmGrain","grainStrength","grainSize","sunNoonR","sunNoon","sunSetR","sunSet","sunsetR","sunset","skyDayR","skyDay","sunGlare","moonGlare","moonLightR","blockR","block","minimum","coloredLight","handLight","handIntensity","handRange","moonLight","moonShadows","ssao","ssaoStrength","ssaoRadius","skyEnabled","skyUpR","skyUp","nightUpR","nightUp","nightStars","aurora","auroraStrength","auroraCount","auroraCurtainR","auroraCrownR","nightNebula","nebulaDeepR","nebulaGlowR","nebulaScale","rainbows","rainbowStrength","endBlackHole","endPlanet","endRingTransparency","endLightElevation","endLightStrength","endHoleSize","endDiscBrightness","endStars","endFog","endFogStrength","endFogColorR","endFogHeight","fogDensity","weather","weatherDrops","wetSurfaces","waterAmplitude","waterSpeed","waterDensity","waterVisibility","waterCaustics","waterRefraction","waterReflection","materials","materialGloss","materialEmission","materialMetal","materialGlass","materialWorldReflections","enabled","bloom","bloomStrength","bloomRadius","dof","autoFocus","focusDistance","dofRadius","motion","motionStrength","flare","flareStrength","vignette","vignetteStrength","chromaStrength","sharpen","sharpenStrength","godrays","raysStrength","raysDensity","raysDistance");
    /** A color's channels stay together: naming the red channel takes green and blue along. */
    private static boolean basic(String key){
        if(BASIC.contains(key))return true;
        if(Set.of("weatherSun","weatherMoon","weatherThunder","weatherThunderMoon","rainDim","weatherNightDim").contains(key))return true;
        return (key.endsWith("G")||key.endsWith("B"))&&BASIC.contains(key.substring(0,key.length()-1)+"R");
    }
    /** Headings inside a feature, in display order. */
    public static final List<String> GROUP_ORDER=List.of("picture","sun","skyLight","moon","moonPhases","misc","shadows","blockLight","coloredLight","hand","ssao",
        "skyDay","skyGlow","skyNight","fog","nebula","celestialSun","celestialMoon","moonDisplay","celestial","aurora","rainbow","endSky","weatherDay","weatherNight","weather","drops","wet","waterWaves","waterOptics","waterEffects",
        "materials","materialGroups","transparent","reflections","general","bloom","godrays","dof","motion","flare","chroma","vignette","sharpen","grain","render","diagnostics");
    private static String sunGroup(String k){
        // The sky's glow around a body sits beside the body's own light fringe.
        if(k.endsWith("Glare"))return k.startsWith("moon")?"celestialMoon":"celestialSun";
        if(k.startsWith("celestialMoonPhase"))return "moonDisplay";
        if(k.startsWith("celestialMoon"))return "celestialMoon";
        if(k.startsWith("celestialSun")||k.equals("celestialHorizon"))return "celestialSun";
        if(k.startsWith("celestial"))return "celestial";
        if(k.startsWith("sunNoon")||k.startsWith("sunSet"))return "sun";
        if(k.startsWith("skyDay")||k.startsWith("skyDusk")||k.startsWith("skyNight"))return "skyLight";
        if(k.startsWith("coloredLight"))return "coloredLight";
        if(k.startsWith("block"))return "blockLight";
        if(k.equals("shadowFill")||k.equals("shadowHalfResolution"))return "shadows";
        if(k.equals("moonFull")||k.equals("moonPartial")||k.equals("moonNew"))return "moonPhases";
        if(k.startsWith("moon"))return "moon";
        if(k.startsWith("hand"))return "hand";
        if(k.startsWith("ssao"))return "ssao";
        if(k.startsWith("sunset"))return "skyGlow";
        if(k.startsWith("skyUp")||k.startsWith("horizon")||k.startsWith("sky"))return "skyDay";
        if(k.startsWith("nightUp")||k.startsWith("nightHorizon")||k.equals("nightStars"))return "skyNight";
        if(k.startsWith("fog"))return "fog";
        if(k.equals("nightNebula")||k.startsWith("nebula"))return "nebula";
        if(k.startsWith("aurora"))return "aurora";
        if(k.startsWith("rainbow"))return "rainbow";
        if(k.startsWith("end"))return "endSky";
        if(k.startsWith("wet"))return "wet";
        if(k.equals("weatherDrops")||k.startsWith("drop")||k.startsWith("stormDrop")||(k.startsWith("rain")&&!k.equals("rainDim")))return "drops";
        if(k.equals("weatherSun")||k.equals("rainDim")||k.equals("weatherThunder"))return "weatherDay";
        if(k.equals("weatherMoon")||k.equals("weatherNightDim")||k.equals("weatherThunderMoon"))return "weatherNight";
        if(k.startsWith("weather"))return "weather";
        if(k.matches("water(Amplitude|Speed|Size)"))return "waterWaves";
        if(k.matches("water(Reflection|Refraction|Density|Visibility|ReflectionQuality|Steps|EdgeFade|FastTrace)"))return "waterOptics";
        if(k.startsWith("water")||k.startsWith("underwater"))return "waterEffects";
        if(k.startsWith("materialWorldReflection")||k.startsWith("materialOpaqueReflection")||k.equals("materialReflections"))return "reflections";
        if(k.matches("material(Metal|Stone|Wood|Plants|Glass|Other|OreGlow)"))return "materialGroups";
        if(k.equals("materialTransparent")||k.equals("materialOpacity"))return "transparent";
        if(k.startsWith("material"))return "materials";
        if(k.equals("renderScale"))return "render";
        return "misc";
    }
    private static final Set<String> SUN_COST=Set.of("underwaterHalf","waterSteps","materialWorldReflectionDistance","auroraQuality","waterReflectionQuality","underwaterQuality","shadowHalfResolution","renderScale","ssaoQuality","coloredLightRange","materialWorldReflectionQuality","materialWorldReflectionResolution","materialWorldReflectionSteps");
    /** The visible sun and moon's first settings; glow, shape and phase details sit under More. */
    private static final Set<String> CELESTIAL_BASIC=Set.of("celestialStyle","celestialSunSize","celestialSunR","celestialSunIntensity","celestialMoonSize","celestialMoonR","celestialMoonIntensity");
    private static final Set<String> SAME_AS_QUALITY=Set.of("waterSteps","materialWorldReflectionSteps");
    private static final Set<String> CAMERA_COST=Set.of("bloomLevels","bloomScale","dofSamples","motionSamples","raysSamples","raysScale");
    public static final Map<String,Rule> RULES=rules();
    private GraphicsCatalog(){}
    private static Map<String,Rule> rules(){
        Map<String,Rule> out=new LinkedHashMap<>();
        for(var p:SunTuning.PARAMS){
            if(p.max()<=p.min())continue;
            boolean quality=SUN_COST.contains(p.key());String group=sunGroup(p.key());
            boolean diagnostic=p.key().equals("shadowFlickerFix")||p.key().equals("shadowContactFix")||p.key().equals("waterFastTrace");if(diagnostic)group="diagnostics";
            // A second control over one step count: the quality setting already halves and quarters it. Quality levels and
            // profiles keep using the value; it only leaves the menu, except in developer view.
            if(SAME_AS_QUALITY.contains(p.key()))group="diagnostics";
            String celestialStem=p.key().matches("celestial(Sun|Moon)[GB]")?p.key().substring(0,p.key().length()-1)+"R":p.key();
            boolean celestialBasic=CELESTIAL_BASIC.contains(celestialStem);
            add(out,"sun."+p.key(),"siliconshaders.shaders.sun.tune."+p.key(),group,p.neutral(),p.min(),p.max(),p.step(),p.toggle(),basic(p.key())||quality||celestialBasic,!diagnostic,quality);
        }
        for(var p:CameraSettings.PARAMS){
            boolean diagnostic=p.key().equals("raysVariant"),quality=CAMERA_COST.contains(p.key());
            add(out,"camera."+p.key(),"siliconshaders.camera."+p.key(),diagnostic?"diagnostics":p.group().name().toLowerCase(Locale.ROOT),
                p.neutral(),p.min(),p.max(),p.step(),p.toggle(),basic(p.key())||quality,!diagnostic,quality);
        }
        // The look's first settings sit up front; lift / gamma / gain, the white point and adaptation details under More.
        Set<String> lookBasic=Set.of("exposure","saturation","contrast","temperature","tint","vibrance","curve","autoExposure");
        for(var p:SunLook.PARAMS)add(out,"look."+p.key(),"siliconshaders.shaders.sun.look."+p.key(),"picture",p.neutral(),p.min(),p.max(),p.step(),false,lookBasic.contains(p.key()),true,false);
        add(out,"shadow.enabled","siliconshaders.shaders.sun.toggle","picture",0,0,1,1,true,true,true,false);
        add(out,"shadow.size","siliconshaders.shaders.sun.quality","shadows",1024,0,4096,1,false,true,true,true);
        add(out,"shadow.distance","siliconshaders.shaders.sun.distance","shadows",64,32,256,16,false,true,true,true);
        add(out,"shadow.levels","siliconshaders.shaders.sun.levels","shadows",3,2,4,1,false,true,true,true);
        add(out,"shadow.smooth","siliconshaders.shaders.sun.smooth","shadows",1,0,1,1,true,true,true,false);
        for(String key:List.of("shadowCulling","filterOptimization","casterSelection","receiverScan","memoryOptimization","shadowMapOptimization","geometryOptimization","farShadowCache","gbuffer","gbufferView"))
            add(out,"shadow."+key,"siliconshaders.shaders.sun."+(key.equals("shadowCulling")?"culling":key),"diagnostics",key.equals("gbufferView")?0:1,0,key.equals("gbufferView")?5:1,1,!key.equals("gbufferView"),true,false,false);
        Map<String,Rule> ordered=new LinkedHashMap<>();for(String key:List.of("shadow.enabled","look.exposure","look.saturation","look.contrast","sun.moonShadows","shadow.size","shadow.distance","shadow.levels"))ordered.put(key,out.get(key));
        // Match the two weather sections: direct light in rain, sky darkening in rain, then the thunder multiplier.
        for(String key:List.of("sun.weatherSun","sun.rainDim","sun.weatherThunder","sun.weatherMoon","sun.weatherNightDim","sun.weatherThunderMoon"))ordered.put(key,out.get(key));
        // The two glows around a body sit side by side, whatever their place in the packet table.
        Map<String,String> besideGlow=Map.of("sun.celestialSunGlow","sun.sunGlare","sun.celestialMoonGlow","sun.moonGlare");
        out.forEach((key,rule)->{if(besideGlow.containsValue(key))return;ordered.putIfAbsent(key,rule);if(besideGlow.containsKey(key))ordered.put(besideGlow.get(key),out.get(besideGlow.get(key)));});
        return Collections.unmodifiableMap(ordered);
    }
    private static void add(Map<String,Rule> out,String key,String label,String group,float neutral,float min,float max,float step,boolean toggle,boolean basic,boolean shareable,boolean quality){out.put(key,new Rule(key,label,group,neutral,min,max,step,toggle,basic,shareable,quality));}
    /** A menu feature: every setting it owns in one place. area: the heading above it in the list. toggle: the setting that switches the whole feature, if any. */
    public record Feature(String id,String area,String toggle){}
    public static final List<Feature> FEATURES=List.of(new Feature("picture","picture",null),
        new Feature("light","light",null),new Feature("shadows","light",null),new Feature("blockLight","light",null),new Feature("hand","light","sun.handLight"),new Feature("ssao","light","sun.ssao"),
        new Feature("sky","sky","sun.skyEnabled"),new Feature("celestial","sky","sun.celestialStyle"),new Feature("aurora","sky","sun.aurora"),new Feature("rainbow","sky","sun.rainbows"),new Feature("endSky","sky",null),
        new Feature("weather","world","sun.weather"),new Feature("rain","world","sun.weatherDrops"),new Feature("wetness","world","sun.wetSurfaces"),new Feature("water","world",null),new Feature("materials","world","sun.materials"),
        new Feature("camera","camera","camera.enabled"),new Feature("godrays","camera","camera.godrays"),new Feature("bloom","camera","camera.bloom"),new Feature("dof","camera","camera.dof"),
        new Feature("motion","camera","camera.motion"),new Feature("flare","camera","camera.flare"),new Feature("chroma","camera","camera.chroma"),new Feature("vignette","camera","camera.vignette"),
        new Feature("sharpen","camera","camera.sharpen"),
        new Feature("grain","camera","camera.filmGrain"),new Feature("render","performance",null));
    /** Within a feature: what it looks like, what it costs, rarely used extras, implementation switches for developers. */
    public enum Tier { LOOK, QUALITY, MORE, DEVELOPER }
    public static Tier tier(Rule r){if(r.group().equals("diagnostics"))return Tier.DEVELOPER;if(r.quality())return Tier.QUALITY;return r.basic()?Tier.LOOK:Tier.MORE;}
    /** The feature a setting belongs to; empty for the shader master switch, which sits in the menu's top bar. */
    public static String feature(Rule r){
        String k=r.key();
        if(k.equals("shadow.enabled"))return "";
        if(k.startsWith("look."))return "picture";
        if(k.equals("camera.enabled"))return "camera";
        if(k.equals("shadow.gbuffer")||k.equals("shadow.gbufferView"))return "render";
        if(k.startsWith("shadow.")||k.equals("sun.shadowFlickerFix")||k.equals("sun.shadowContactFix"))return "shadows";
        if(k.equals("sun.waterFastTrace")||k.equals("sun.waterSteps"))return "water";
        if(k.equals("sun.materialWorldReflectionSteps"))return "materials";
        if(k.startsWith("camera."))return CameraSettings.PARAMS.get(CameraSettings.index(k.substring(7))).group().name().toLowerCase(Locale.ROOT);
        return switch(r.group()){
            case "shadows"->"shadows";case "blockLight","coloredLight"->"blockLight";case "hand"->"hand";case "ssao"->"ssao";
            case "skyDay","skyGlow","skyNight","fog","nebula"->"sky";case "celestial","celestialSun","celestialMoon","moonDisplay"->"celestial";case "aurora"->"aurora";case "rainbow"->"rainbow";case "endSky"->"endSky";
            case "weather","weatherDay","weatherNight"->"weather";case "drops"->"rain";case "wet"->"wetness";case "waterWaves","waterOptics","waterEffects"->"water";
            case "materials","materialGroups","transparent","reflections"->"materials";case "render"->"render";
            default->"light";
        };
    }
    /** A switch and the settings that do nothing while it is off (beyond the feature's own switch). */
    private static final Map<String,List<String>> SWITCHES=Map.ofEntries(
        Map.entry("sun.moonShadows",List.of("sun.moonLightR","sun.moonLightG","sun.moonLightB","sun.moonLight")),
        Map.entry("sun.coloredLight",List.of("sun.coloredLightRange")),
        Map.entry("sun.skyEnabled",List.of("sun.celestialStyle","sun.sunGlare","sun.moonGlare","sun.aurora","sun.rainbows","sun.endBlackHole","sun.endPlanet","sun.endFog","sun.endStars")),
        Map.entry("sun.endPlanet",List.of("sun.endRingTransparency")),
        Map.entry("sun.endFog",List.of("sun.endFogStrength","sun.endFogDistance","sun.endFogBrightness","sun.endFogColorR","sun.endFogColorG","sun.endFogColorB","sun.endFogHeight","sun.endFogThickness","sun.endFogSize","sun.endFogWisps","sun.endFogSpeed","sun.endFogEmber","sun.endFogHoleLight")),
        Map.entry("sun.endFogEmber",List.of("sun.endFogEmberColorR","sun.endFogEmberColorG","sun.endFogEmberColorB")),
        Map.entry("sun.endBlackHole",List.of("sun.endFogHoleLight","sun.endLightElevation","sun.endLightStrength","sun.endHoleSize","sun.endDiscBrightness")),
        Map.entry("sun.celestialMoonPhases",List.of("sun.celestialMoonPhaseAngle","sun.celestialMoonPhaseOffset","sun.celestialMoonPhaseSoftness")),
        Map.entry("sun.auroraCurved",List.of("sun.auroraCurvature","sun.auroraCurveExtent")),
        Map.entry("sun.nightNebula",List.of("sun.nebulaDeepR","sun.nebulaDeepG","sun.nebulaDeepB","sun.nebulaGlowR","sun.nebulaGlowG","sun.nebulaGlowB","sun.nebulaScale","sun.nebulaSpeed")),
        Map.entry("sun.weather",List.of("sun.weatherDrops","sun.wetSurfaces")),
        Map.entry("sun.weatherDrops",List.of("sun.rainWind","sun.dropOpacity","sun.dropBrightness","sun.stormDropOpacity","sun.stormDropBrightness","sun.dropWidth","sun.dropLength","sun.dropVariation")),
        Map.entry("sun.rainWind",List.of("sun.rainAngle","sun.rainDirection","sun.rainGusts","sun.rainWindSpeed")),
        Map.entry("sun.wetSurfaces",List.of("sun.wetStrength","sun.wetPuddles","sun.wetReflectionRadius")),
        Map.entry("sun.materialDetail",List.of("sun.materialDetailStrength")),
        Map.entry("sun.materialOpaqueReflections",List.of("sun.materialOpaqueReflectionStrength")),
        Map.entry("sun.materialWorldReflections",List.of("sun.materialWorldReflectionStrength","sun.materialWorldReflectionQuality","sun.materialWorldReflectionDistance","sun.materialWorldReflectionSteps","sun.materialWorldReflectionResolution")),
        Map.entry("sun.materialGlass",List.of("sun.materialTransparent")),
        Map.entry("sun.materialTransparent",List.of("sun.materialOpacity","sun.materialReflections")),
        Map.entry("shadow.size",List.of("shadow.distance","shadow.levels","sun.shadowHalfResolution")),
        Map.entry("shadow.gbuffer",List.of("shadow.gbufferView")),
        Map.entry("camera.autoFocus",List.of("camera.focusSpeed")),
        Map.entry("look.autoExposure",List.of("look.autoExposureSpeed","look.autoExposureRange")));
    private static final Map<String,List<String>> REQUIRES=requirements();
    private static Map<String,List<String>> requirements(){
        Map<String,List<String>> out=new HashMap<>();
        for(var rule:RULES.values()){
            String f=feature(rule);List<String> needs=new ArrayList<>();
            // The sky's glows around sun and moon also work with the vanilla textures: only the custom bodies need their switch.
            boolean glare=rule.key().equals("sun.sunGlare")||rule.key().equals("sun.moonGlare");
            for(var feature:FEATURES)if(feature.id().equals(f)&&feature.toggle()!=null&&!feature.toggle().equals(rule.key())&&!glare)needs.add(feature.toggle());
            if(rule.key().startsWith("camera.")&&!rule.key().equals("camera.enabled"))needs.add("camera.enabled");
            SWITCHES.forEach((toggle,settings)->{if(settings.contains(rule.key()))needs.add(toggle);});
            out.put(rule.key(),List.copyOf(needs));
        }
        return Collections.unmodifiableMap(out);
    }
    /** The settings that must be above 0 for this one to have any effect. */
    public static List<String> requires(String key){return REQUIRES.getOrDefault(key,List.of());}
    private static final Map<String,List<Rule>> BY_FEATURE=byFeature();
    private static Map<String,List<Rule>> byFeature(){
        Map<String,List<Rule>> out=new HashMap<>();
        for(var rule:RULES.values())out.computeIfAbsent(feature(rule),f->new ArrayList<>()).add(rule);
        out.replaceAll((f,rules)->List.copyOf(rules));return Collections.unmodifiableMap(out);
    }
    public static List<Rule> rules(String feature){return BY_FEATURE.getOrDefault(feature,List.of());}
    /** The stem of a colour's R, G or B channel; null for anything else. */
    public static String colorStem(String key){
        if(key.isEmpty()||"RGB".indexOf(key.charAt(key.length()-1))<0)return null;
        String stem=key.substring(0,key.length()-1);
        return RULES.containsKey(stem+"R")&&RULES.containsKey(stem+"G")&&RULES.containsKey(stem+"B")?stem:null;
    }
    public static boolean differs(Rule rule,float value){return Math.abs(value-rule.neutral())>Math.max(rule.step()*.5f,1e-4f);}
    /**
     * What a setting's own switch costs, only where the full benchmark (M2, 1920x1200) shows it clearly beyond that run's own
     * drift of about 1 ms: 2 about 2 ms or more, 1 about 1 to 2 ms in the scene it matters to. Everything else: no claim.
     */
    private static final Map<String,Integer> COSTS=Map.of("shadow.size",2,"sun.waterReflection",2,"sun.waterRefraction",2,"camera.motion",2,"camera.dof",2,"sun.materials",2,
        "sun.ssao",1,"sun.coloredLight",1,"sun.materialWorldReflections",1);
    public static int cost(String key){return COSTS.getOrDefault(key,0);}
    /** Saves the most of anything measured: 67 % render scale took 3 to 10 ms off a frame. */
    public static boolean saves(String key){return key.equals("sun.renderScale");}
    private record Range(float low,float high){}
    /** Values that are allowed, but change the picture so much they are easy to mistake for a problem. */
    private static final Map<String,Range> USUAL=Map.of("sun.endFogStrength",new Range(0,1.5f),"sun.endFogBrightness",new Range(0,2),"sun.endFogDistance",new Range(64,512),"sun.endFogThickness",new Range(0,2),
        "sun.endFogEmber",new Range(0,2),"sun.fogDensity",new Range(0,.015f),"look.exposure",new Range(.5f,2),"look.saturation",new Range(.25f,1.5f),"look.contrast",new Range(.7f,1.4f));
    public static boolean extreme(String key,float value){Range usual=USUAL.get(key);return usual!=null&&(value<usual.low()-1e-4f||value>usual.high()+1e-4f);}
    /** Standard follows the shipped profile. Levels set quality and their explicit effect choices; render scale stays manual. */
    public static final List<Map<String,Float>> QUALITY_LEVELS=qualityLevels();
    private static List<Map<String,Float>> qualityLevels(){
        var low=effects(level(1024,64,2,1,8,.25f,2,2,2,12,2,2,12,32,3,2,2,.25f,8,8,1),false,false);
        var medium=effects(level(2048,128,3,0,16,.25f,1,1,1,16,1,1,20,48,4,1,3,.5f,12,12,1),false,true);
        var high=effects(level(4096,224,4,0,48,.5f,0,0,0,32,0,0,40,96,10,0,4,.5f,16,16,1),false,true);
        var ultra=effects(level(4096,256,4,0,64,.5f,0,0,0,48,0,0,48,128,14,0,5,.5f,24,24,0),true,true);
        Map<String,Float> shipped=DefaultProfile.source().values(),standard=new LinkedHashMap<>();
        for(String key:low.keySet()){Rule rule=RULES.get(key);standard.put(key,nearest(rule,shipped.getOrDefault(key,rule.neutral())));}
        return List.of(Collections.unmodifiableMap(standard),low,medium,high,ultra);
    }
    private static Map<String,Float> level(float size,float distance,float levels,float halfShadow,float raySamples,float rayScale,float aurora,float waterReflection,
        float underwater,float waterSteps,float reflectionQuality,float reflectionResolution,float reflectionSteps,float reflectionDistance,float coloredRange,float ssao,float bloomLevels,float bloomScale,float dofSamples,float motionSamples,float underwaterHalf){
        Map<String,Float> m=new LinkedHashMap<>();
        m.put("shadow.size",size);m.put("shadow.distance",distance);m.put("shadow.levels",levels);m.put("sun.shadowHalfResolution",halfShadow);
        m.put("camera.raysSamples",raySamples);m.put("camera.raysScale",rayScale);m.put("sun.auroraQuality",aurora);
        m.put("sun.waterReflectionQuality",waterReflection);m.put("sun.underwaterQuality",underwater);m.put("sun.waterSteps",waterSteps);
        m.put("sun.materialWorldReflectionQuality",reflectionQuality);m.put("sun.materialWorldReflectionResolution",reflectionResolution);
        m.put("sun.materialWorldReflectionSteps",reflectionSteps);m.put("sun.materialWorldReflectionDistance",reflectionDistance);m.put("sun.coloredLightRange",coloredRange);
        m.put("sun.ssaoQuality",ssao);m.put("camera.bloomLevels",bloomLevels);m.put("camera.bloomScale",bloomScale);m.put("camera.dofSamples",dofSamples);m.put("camera.motionSamples",motionSamples);
        m.put("sun.underwaterHalf",underwaterHalf);
        return Collections.unmodifiableMap(m);
    }
    private static Map<String,Float> effects(Map<String,Float> quality,boolean coloredLight,boolean waterReflection){
        Map<String,Float> out=new LinkedHashMap<>(quality);out.put("sun.ssao",0f);out.put("sun.coloredLight",coloredLight?1f:0f);
        Rule reflection=RULES.get("sun.waterReflection");
        out.put(reflection.key(),waterReflection?nearest(reflection,DefaultProfile.source().values().getOrDefault(reflection.key(),reflection.neutral())):0f);
        return Collections.unmodifiableMap(out);
    }
    public static Map<String,Float> defaults(){Map<String,Float> out=new LinkedHashMap<>();RULES.forEach((k,v)->out.put(k,v.neutral()));return out;}
    /**
     * Saved codes and profiles name settings, and they are never reissued, so a setting's name is permanent. A setting that
     * has to be renamed anyway goes here (old name to new name), or old codes would silently lose its value.
     */
    private static final Map<String,String> RENAMED=Map.of();
    public static Rule rule(String savedName){return RULES.get(RENAMED.getOrDefault(savedName,savedName));}
    private static boolean inScope(Rule rule,GraphicsProfile.Scope scope){return rule.shareable()&&(scope==GraphicsProfile.Scope.ALL||!rule.quality());}
    /** The closest value this version allows: a code from when a range was wider still applies. */
    private static float nearest(Rule rule,float value){
        float fitted=rule.normalize(Math.clamp(value,rule.min(),rule.max()));
        if(!rule.key().equals("shadow.size"))return fitted;
        float best=0;for(float size:new float[]{512,1024,2048,4096})if(Math.abs(size-fitted)<Math.abs(best-fitted))best=size;return best;
    }
    public static Map<String,Float> portable(Map<String,Float> source,GraphicsProfile.Scope scope){
        Map<String,Float> out=new TreeMap<>();source.forEach((key,value)->{Rule rule=rule(key);if(rule!=null&&inScope(rule,scope))out.put(rule.key(),nearest(rule,value));});return out;
    }
    /** A profile applies only what it names: everything else, new features above all, stays as it is. */
    public static Map<String,Float> merge(Map<String,Float> current,GraphicsProfile profile){
        Map<String,Float> next=new LinkedHashMap<>(current);Map<String,Float> known=portable(profile.values(),profile.scope());
        if(known.isEmpty())throw new IllegalArgumentException("profile.unsupported");next.putAll(known);validate(next);return next;
    }
    public static void validate(Map<String,Float> values){
        for(var e:values.entrySet()){Rule rule=RULES.get(e.getKey());if(rule!=null)rule.normalize(e.getValue());}
        Float size=values.get("shadow.size");if(size!=null&&size!=0&&size!=512&&size!=1024&&size!=2048&&size!=4096)throw new IllegalArgumentException("profile.range");
    }
    /**
     * How a saved code fits this version. unchanged: settings of its scope it has no value for, so they stay as they are (a
     * colour counts once). skipped: its values this version has no use for. adjusted: its values moved to the closest one allowed.
     */
    public record Fit(int unchanged,int skipped,int adjusted){}
    public static Fit fit(GraphicsProfile profile){
        Map<String,Float> known=portable(profile.values(),profile.scope());int unchanged=0,adjusted=0;
        for(var rule:RULES.values()){
            if(!inScope(rule,profile.scope())||known.containsKey(rule.key()))continue;
            if(colorStem(rule.key())==null||rule.key().endsWith("R"))unchanged++;
        }
        for(var e:profile.values().entrySet()){
            Rule rule=rule(e.getKey());
            if(rule!=null&&known.containsKey(rule.key())&&Math.abs(known.get(rule.key())-e.getValue())>Math.max(rule.step()*.51f,1e-4f))adjusted++;
        }
        return new Fit(unchanged,profile.values().size()-known.size(),adjusted);
    }
}
