package siliconshaders.metal.bench;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import siliconshaders.metal.sun.SunSettings;
import siliconshaders.metal.sun.SunTuning;

/**
 * What a benchmark run measures: fixed scenes (a spot of the benchmark world, a time of day, a camera pitch) times
 * settings variants. Every variant starts from a base (the shipped defaults, the player's own settings, or the shader
 * off) and changes single SunSettings fields or SunTuning values, so each one isolates one switch.
 */
public final class BenchPlan {
    private BenchPlan() {}
    /** A camera spot of the benchmark world (BenchSpots); only: the variants this scene runs (null = all). */
    public record Scene(String id,String label,String spot,int time,float pitch,List<String> only) {}
    public enum Base { STANDARD, OWN, VANILLA }
    /** path: render path overrides for this variant (OwnProbe keys, PATH_KEYS), equal pictures by other means; they need Profile.PATH_VARIANTS at launch. */
    public record Variant(String id,String label,Base base,Map<String,Object> sun,Map<String,Float> tuning,Map<String,Float> camera,Map<String,Float> look,Map<String,String> path) {
        public Variant(String id,String label,Base base,Map<String,Object> sun,Map<String,Float> tuning,Map<String,Float> camera,Map<String,Float> look){this(id,label,base,sun,tuning,camera,look,Map.of());}
        public Variant(String id,String label,Base base,Map<String,Object> sun,Map<String,Float> tuning,Map<String,Float> camera){this(id,label,base,sun,tuning,camera,Map.of());}
        public Variant(String id,String label,Base base,Map<String,Object> sun,Map<String,Float> tuning){this(id,label,base,sun,tuning,Map.of());}
    }
    /** window: "fixed" (WINDOW_WIDTH x WINDOW_HEIGHT), "keep", or "fullscreen" (exclusive, with the exact display mode "WxH"; "desktop": plain fullscreen at the display's own resolution, no mode change; empty mode keeps the player's). */
    public record Request(String profile,int seconds,String label,boolean quit,String window,String mode,String power,List<Scene> scenes,List<Variant> variants) {
        public boolean usesPaths() { return variants.stream().anyMatch(v->!v.path().isEmpty()); }
        /** Rough minutes for the menu: settle, warm-up and measurement of every pair, plus loading. */
        public int minutes() {
            int pairs=0;for(Scene s:scenes)for(Variant v:variants)if(s.only()==null||s.only().contains(v.id()))pairs++;
            return (int)Math.ceil((pairs*(WARM_SECONDS+seconds)+scenes.size()*8+60)/60.0);
        }
    }
    public static final int WARM_SECONDS=3;
    public static final int WINDOW_WIDTH=1280,WINDOW_HEIGHT=800;
    public static final String FULLSCREEN_MODE="1920x1200",DESKTOP_MODE="desktop";

    // Daytime 4000: the sun at about 45 degrees, long shadows. Night 18000: midnight, full moon on day 0.
    static final List<Scene> SCENES=List.of(
        new Scene("land","Landschaft","land",4000,12,null),
        new Scene("wald","Wald","wald",4000,0,null),
        new Scene("wasser","Wasser","wasser",4000,25,null),
        new Scene("unterwasser","Unter Wasser","unterwasser",4000,0,null),
        new Scene("nacht","Nacht","land",18000,12,null),
        // Repeat the first reference to detect drift; thermal probes help interpret it.
        new Scene("land-ende","Landschaft (Ende)","land",4000,12,List.of("standard")));

    private static Variant v(String id,String label,Base base) { return new Variant(id,label,base,Map.of(),Map.of()); }
    private static Variant sun(String id,String label,String field,Object value) { return new Variant(id,label,Base.STANDARD,Map.of(field,value),Map.of()); }
    private static Variant tune(String id,String label,String key,float value) { return new Variant(id,label,Base.STANDARD,Map.of(),Map.of(key,value)); }
    static final Variant VANILLA=v("vanilla","Shader aus",Base.VANILLA),STANDARD=v("standard","Standard",Base.STANDARD),OWN=v("eigene","Deine Einstellungen",Base.OWN);
    static final List<Variant> BASIC=List.of(VANILLA,STANDARD,OWN,sun("schatten-aus","Schatten aus","shadowSize",0),sun("gbuffer-aus","G-Buffer aus","gbuffer",false));
    static final List<Variant> FULL;
    static {
        List<Variant> all=new ArrayList<>(BASIC);
        all.add(sun("schatten-512","Schatten 512","shadowSize",512));all.add(sun("schatten-2048","Schatten 2048","shadowSize",2048));
        all.add(sun("schatten-4096","Schatten 4096","shadowSize",4096));
        all.add(sun("kaskaden-2","2 Kaskaden","shadowLevels",2));all.add(sun("kaskaden-4","4 Kaskaden","shadowLevels",4));
        all.add(sun("weite-32","Schattenweite 32","shadowDistance",32));all.add(sun("weite-128","Schattenweite 128","shadowDistance",128));
        all.add(sun("weich-aus","Weiche Schatten aus","smooth",false));
        all.add(sun("culling-aus","Schatten-Culling aus","shadowCulling",false));
        all.add(sun("filter-opt-aus","Filter-Optimierung aus","filterOptimization",false));
        all.add(sun("caster-auswahl-aus","Werfer-Auswahl aus","casterSelection",false));
        all.add(sun("empfaenger-scan-aus","Empfänger-Scan aus","receiverScan",false));
        all.add(sun("speicher-opt-aus","Speicher-Optimierung aus","memoryOptimization",false));
        all.add(sun("schattenkarte-opt-aus","Schattenkarten-Optimierung aus","shadowMapOptimization",false));
        all.add(sun("geometrie-opt-aus","Geometrie-Optimierung aus","geometryOptimization",false));
        all.add(sun("fern-cache-aus","Fern-Cache aus","farShadowCache",false));
        all.add(tune("himmel-aus","Eigener Himmel aus","skyEnabled",0));
        all.add(tune("mondschatten-aus","Mondschatten aus","moonShadows",0));
        all.add(tune("spiegelung-aus","Wasser-Spiegelung 0","waterReflection",0));
        all.add(tune("brechung-aus","Wasser-Brechung 0","waterRefraction",0));
        all.add(tune("wasser-schnell-aus","Schnelle Wasser-Suche aus","waterFastTrace",0));
        all.add(tune("unterwasser-voll","Unterwasser in voller Auflösung","underwaterHalf",0));
        all.add(tune("spiegel-8","Spiegelung 8 Schritte","waterSteps",8));all.add(tune("spiegel-16","Spiegelung 16 Schritte","waterSteps",16));
        all.add(tune("spiegel-48","Spiegelung 48 Schritte","waterSteps",48));
        FULL=List.copyOf(all);
    }

    /** Feature coverage is deliberately paired by scene rather than every parameter combination. */
    static final List<Variant> DIAGNOSTIC;
    static final List<Scene> DIAGNOSTIC_SCENES;
    static {
        List<Variant> all=new ArrayList<>(FULL);
        all.add(tune("metalfx-85","MetalFX 85 %","renderScale",1));
        all.add(tune("metalfx-67","MetalFX 67 %","renderScale",2));
        all.add(tune("metalfx-50","MetalFX 50 %","renderScale",3));
        all.add(tune("ssao-aus","SSAO aus","ssao",0));
        all.add(tune("ssao-niedrig","SSAO niedrig","ssaoQuality",0));
        all.add(tune("ssao-hoch","SSAO hoch","ssaoQuality",2));
        all.add(tune("schatten-halb","Schatten halbe Auflösung","shadowHalfResolution",1));
        all.add(tune("materialien-aus","Materialien aus","materials",0));
        all.add(tune("material-ssr-aus","Material-Reflexionen aus","materialWorldReflections",0));
        all.add(tune("farblicht-aus","Farbiges Blocklicht aus","coloredLight",0));
        all.add(tune("farblicht-weit","Farbiges Blocklicht Reichweite 8","coloredLightRange",8));
        all.add(tune("wetter-aus","Wettereffekte aus","weather",0));
        all.add(tune("tropfen-aus","Regentropfen aus","weatherDrops",0));
        all.add(tune("nass-aus","Nasse Oberflächen aus","wetSurfaces",0));
        all.add(tune("spritzer-an","Regenspritzer an","rainSplashes",1));
        all.add(tune("polarlicht-an","Polarlicht an","aurora",3));
        all.add(tune("polarlicht-aus","Polarlicht aus","aurora",0));
        for(String key:List.of("bloom","dof","motion","flare","chroma","vignette","sharpen","godrays"))
            all.add(new Variant("kamera-"+key,key,Base.STANDARD,Map.of(),Map.of(),Map.of(key,1f)));
        all.add(new Variant("bloom-hdr","HDR Bloom",Base.STANDARD,Map.of(),Map.of(),Map.of("bloom",1f,"bloomVariant",1f)));
        all.add(new Variant("rays-volumetrisch","Volumetrische Godrays",Base.STANDARD,Map.of(),Map.of(),Map.of("godrays",1f,"raysVariant",1f)));
        all.add(new Variant("kamera-alle","Alle Kameraeffekte + HDR",Base.STANDARD,Map.of(),Map.of(),
            Map.of("bloom",1f,"bloomVariant",1f,"dof",1f,"motion",1f,"flare",1f,"chroma",1f,"vignette",1f,"sharpen",1f,"godrays",1f,"raysVariant",1f)));
        all.add(new Variant("fx-hdr-rays","MetalFX 67 % + HDR + Godrays",Base.STANDARD,Map.of(),Map.of("renderScale",2f),
            Map.of("bloom",1f,"bloomVariant",1f,"godrays",1f,"raysVariant",1f)));
        for(int phase:List.of(0,90,180))all.add(new Variant("mond-"+phase,"Mondphase "+phase+"° + Strahlen",Base.STANDARD,Map.of(),
            Map.of("celestialStyle",1f,"celestialMoonPhaseOffset",(float)phase),Map.of("flare",1f,"godrays",1f,"raysVariant",1f)));
        for(int curve=1;curve<=3;curve++)all.add(new Variant("tonkurve-"+curve,"Tonkurve "+curve,Base.STANDARD,Map.of(),Map.of(),Map.of(),Map.of("curve",(float)curve)));
        all.add(new Variant("auto-belichtung","Automatische Belichtung",Base.STANDARD,Map.of(),Map.of(),Map.of(),Map.of("autoExposure",1f)));
        DIAGNOSTIC=List.copyOf(all);
        List<String> land=new ArrayList<>();for(Variant v:all)if(!v.id().startsWith("mond-")&&!List.of("wetter-aus","tropfen-aus","nass-aus","spritzer-an","polarlicht-an","polarlicht-aus").contains(v.id()))land.add(v.id());
        DIAGNOSTIC_SCENES=List.of(
            new Scene("land","Landschaft","land",4000,12,List.copyOf(land)),
            new Scene("wald","Wald","wald",4000,0,List.of("standard","schatten-aus","ssao-aus","rays-volumetrisch","metalfx-67")),
            new Scene("wasser","Wasser","wasser",4000,25,List.of("standard","spiegelung-aus","brechung-aus","wasser-schnell-aus","spiegel-8","spiegel-48","material-ssr-aus","metalfx-67","fx-hdr-rays")),
            new Scene("unterwasser","Unter Wasser","unterwasser",4000,0,List.of("standard","unterwasser-voll","kamera-godrays","rays-volumetrisch","metalfx-67")),
            new Scene("nacht","Nacht","land",18000,-25,List.of("standard","mondschatten-aus","polarlicht-an","polarlicht-aus","mond-0","mond-90","mond-180")),
            new Scene("regen","Regen","land",4000,12,List.of("standard","wetter-aus","tropfen-aus","nass-aus","spritzer-an","metalfx-67")),
            new Scene("objekte","Materialien, Licht und 64 Objekte","objekte",18000,10,List.of("vanilla","standard","schatten-aus","farblicht-aus","farblicht-weit","materialien-aus","material-ssr-aus","bloom-hdr","metalfx-67")),
            new Scene("land-ende","Landschaft (Ende)","land",4000,12,List.of("standard")));
    }

    /** The overrides a request may set, as the OwnProbe keys that read them per frame; anything sized or built once can't take part. */
    static void validatePath(String key,String value) {
        boolean valid=switch(key) {
            case "frag.uocc","frag.uoccSizeSwitch","sun.receiverGrid" -> value.equals("true")||value.equals("false");
            case "frag.vGroupSh" -> value.matches("[0-3]");
            case "tl.a1Split" -> value.matches("\\d{1,2}");
            default -> throw new IllegalArgumentException("Unknown render path "+key);
        };
        if(!valid)throw new IllegalArgumentException("Invalid render path value "+key+"="+value);
    }
    private static Variant path(String id,String label,Base base,String key,String value) { return new Variant(id,label,base,Map.of(),Map.of(),Map.of(),Map.of(),Map.of(key,value)); }
    /**
     * Which of the equal-picture render paths is faster on this Mac, with the shader on and off, at the display's own resolution: the
     * unit occlusion and its size cutoff, grouped draws, the a1 split, the receiver grid. Each pair forces both sides, so the result
     * doesn't depend on what this chip's GpuTuning policy picks. The -ende repeats give each scene its own noise and drift.
     */
    static final List<Variant> PATHS;
    static final List<Scene> PATH_SCENES;
    static {
        List<Variant> off=List.of(VANILLA,
            path("aus-uocc-aus","Aus: Occlusion aus",Base.VANILLA,"frag.uocc","false"),path("aus-uocc-an","Aus: Occlusion an",Base.VANILLA,"frag.uoccSizeSwitch","false"),
            path("aus-vgroup-aus","Aus: Gruppierung aus",Base.VANILLA,"frag.vGroupSh","0"),path("aus-vgroup-2","Aus: Gruppierung 2",Base.VANILLA,"frag.vGroupSh","1"),
            path("aus-a1-aus","Aus: A1-Split aus",Base.VANILLA,"tl.a1Split","0"),path("aus-a1-30","Aus: A1-Split 30",Base.VANILLA,"tl.a1Split","30"),
            v("vanilla-ende","Shader aus (Wiederholung)",Base.VANILLA));
        List<Variant> on=List.of(STANDARD,
            path("pfad-uocc-aus","Occlusion aus",Base.STANDARD,"frag.uocc","false"),path("pfad-uocc-an","Occlusion an",Base.STANDARD,"frag.uoccSizeSwitch","false"),
            path("pfad-vgroup-aus","Gruppierung aus",Base.STANDARD,"frag.vGroupSh","0"),path("pfad-vgroup-2","Gruppierung 2",Base.STANDARD,"frag.vGroupSh","1"),
            path("pfad-a1-aus","A1-Split aus",Base.STANDARD,"tl.a1Split","0"),path("pfad-a1-30","A1-Split 30",Base.STANDARD,"tl.a1Split","30"),
            path("pfad-grid-aus","Empfänger-Grid aus",Base.STANDARD,"sun.receiverGrid","false"),path("pfad-grid-an","Empfänger-Grid an",Base.STANDARD,"sun.receiverGrid","true"));
        // What the shader's time goes to, where the paths alone can't explain a missing gain.
        List<Variant> cost=List.of(sun("schatten-aus","Schatten aus","shadowSize",0),sun("gbuffer-aus","G-Buffer aus","gbuffer",false),
            tune("ssao-aus","SSAO aus","ssao",0),tune("materialien-aus","Materialien aus","materials",0));
        Variant end=v("standard-ende","Standard (Wiederholung)",Base.STANDARD);
        List<Variant> all=new ArrayList<>(off);all.addAll(on);all.addAll(cost);all.add(end);
        PATHS=List.copyOf(all);
        List<String> paths=new ArrayList<>();for(Variant x:off)paths.add(x.id());for(Variant x:on)paths.add(x.id());paths.add(end.id());
        PATH_SCENES=List.of(
            new Scene("land","Landschaft","land",4000,12,null),
            new Scene("wald","Wald","wald",4000,0,List.copyOf(paths)),
            new Scene("wasser","Wasser","wasser",4000,25,List.of("vanilla","aus-uocc-aus","aus-uocc-an","vanilla-ende","standard","pfad-uocc-aus","pfad-uocc-an","standard-ende")),
            new Scene("land-ende","Landschaft (Ende)","land",4000,12,List.of("standard")));
    }
    public static Request profile(String profile,boolean quit) {
        return switch(profile) {
            case "diagnose" -> new Request("diagnose",8,"",quit,"fullscreen",FULLSCREEN_MODE,"",DIAGNOSTIC_SCENES,DIAGNOSTIC);
            case "pfade" -> new Request("pfade",8,"",quit,"fullscreen",DESKTOP_MODE,"",PATH_SCENES,PATHS);
            case "kurz" -> new Request("kurz",8,"",quit,"fullscreen",FULLSCREEN_MODE,"",List.of(SCENES.get(0),SCENES.get(1),SCENES.get(2)),List.of(VANILLA,STANDARD));
            case "voll" -> new Request("voll",8,"",quit,"fullscreen",FULLSCREEN_MODE,"",SCENES,FULL);
            default -> new Request("standard",10,"",quit,"fullscreen",FULLSCREEN_MODE,"",SCENES,BASIC);
        };
    }

    /** config/siliconshaders-benchmark.json: {"profile","seconds","label","quit","window":"fixed"|"keep"|"fullscreen","mode":"WxH","power","scenes":[ids],"variants":[ids or objects]}. */
    public static Request parse(JsonObject o) {
        Request base=profile(text(o,"profile","standard"),o.has("quit")&&o.get("quit").getAsBoolean());
        int seconds=o.has("seconds")?Math.clamp(o.get("seconds").getAsInt(),3,120):base.seconds();
        String window=text(o,"window",base.window());if(!window.equals("keep")&&!window.equals("fullscreen"))window="fixed";
        String mode=text(o,"mode",window.equals("fullscreen")?base.mode():"");if(!mode.isEmpty()&&!mode.equals(DESKTOP_MODE)&&!mode.matches("\\d{3,5}x\\d{3,5}"))throw new IllegalArgumentException("Mode must be WxH or desktop: "+mode);
        List<Scene> scenes=base.scenes();
        if(o.has("scenes")) {
            scenes=new ArrayList<>();
            for(JsonElement e:o.getAsJsonArray("scenes")) {
                String id=e.getAsString();Scene found=null;for(Scene s:(base.profile().equals("diagnose")?DIAGNOSTIC_SCENES:base.profile().equals("pfade")?PATH_SCENES:SCENES))if(s.id().equals(id))found=s;
                if(found==null)throw new IllegalArgumentException("Unknown scene "+id);scenes.add(found);
            }
        }
        List<Variant> variants=base.variants();
        if(o.has("variants")) {
            variants=new ArrayList<>();
            for(JsonElement e:o.getAsJsonArray("variants"))variants.add(e.isJsonPrimitive()?known(e.getAsString()):custom(e.getAsJsonObject()));
        }
        if(scenes.isEmpty()||variants.isEmpty())throw new IllegalArgumentException("Benchmark needs scenes and variants");
        java.util.Set<String> ids=new java.util.HashSet<>();for(Variant v:variants)if(!ids.add(v.id()))throw new IllegalArgumentException("Duplicate variant "+v.id());
        boolean any=false;for(Scene scene:scenes)for(Variant v:variants)if(scene.only()==null||scene.only().contains(v.id()))any=true;
        if(!any)throw new IllegalArgumentException("No measurements match the scene filters");
        return new Request(base.profile(),seconds,text(o,"label","").replaceAll("[^A-Za-z0-9._-]","-"),base.quit(),window,mode,text(o,"power",""),List.copyOf(scenes),List.copyOf(variants));
    }
    private static String text(JsonObject o,String key,String fallback) { return o.has(key)?o.get(key).getAsString():fallback; }
    private static Variant known(String id) {
        for(Variant v:DIAGNOSTIC)if(v.id().equals(id))return v;
        for(Variant v:PATHS)if(v.id().equals(id))return v;
        throw new IllegalArgumentException("Unknown variant "+id);
    }
    private static Variant custom(JsonObject o) {
        String id=o.get("id").getAsString();
        Base base=Base.valueOf(text(o,"base","standard").toUpperCase(java.util.Locale.ROOT));
        Map<String,Object> sun=new LinkedHashMap<>();
        if(o.has("sun"))for(var e:o.getAsJsonObject("sun").entrySet()) {
            var p=e.getValue().getAsJsonPrimitive();sun.put(e.getKey(),p.isBoolean()?(Object)p.getAsBoolean():(Object)p.getAsInt());
        }
        Map<String,Float> tuning=new LinkedHashMap<>();
        if(o.has("tuning"))for(var e:o.getAsJsonObject("tuning").entrySet()) {
            if(SunTuning.index(e.getKey())<0)throw new IllegalArgumentException("Unknown tuning value "+e.getKey());
            float value=e.getValue().getAsFloat();var parameter=SunTuning.PARAMS.get(SunTuning.index(e.getKey()));
            if(!Float.isFinite(value)||value<parameter.min()||value>parameter.max())throw new IllegalArgumentException("Invalid tuning value "+e.getKey());
            tuning.put(e.getKey(),value);
        }
        Map<String,Float> camera=new LinkedHashMap<>();
        if(o.has("camera"))for(var e:o.getAsJsonObject("camera").entrySet()){
            siliconshaders.metal.camera.CameraSettings.index(e.getKey());float value=e.getValue().getAsFloat();
            if(!Float.isFinite(value))throw new IllegalArgumentException("Invalid camera setting "+e.getKey());camera.put(e.getKey(),value);
        }
        Map<String,String> path=new LinkedHashMap<>();
        if(o.has("path"))for(var e:o.getAsJsonObject("path").entrySet()){
            String value=e.getValue().getAsString();validatePath(e.getKey(),value);path.put(e.getKey(),value);
        }
        Variant v=new Variant(id,text(o,"label",id),base,sun,tuning,Map.copyOf(camera),Map.of(),Map.copyOf(path));
        with(new SunSettings.State(true,1024,true),sun);  // reject unknown fields now, not mid-run
        return v;
    }

    /** The settings a variant runs with: its base, then its single changes. */
    static SunSettings.State state(Variant v,SunSettings.State own) {
        SunSettings.State base=switch(v.base()) {
            case STANDARD,VANILLA -> new SunSettings.State(true,1024,true);
            case OWN -> with(own,Map.of("enabled",true,"gbufferView",0));
        };
        if(v.base()==Base.VANILLA)base=with(base,Map.of("enabled",false));
        return with(base,v.sun());
    }
    static float[] tuning(Variant v,float[] own) {
        float[] values=new float[SunTuning.COUNT];
        for(int i=0;i<values.length;i++)values[i]=v.base()==Base.OWN?own[i]:SunTuning.PARAMS.get(i).neutral();
        for(var e:v.tuning().entrySet())values[SunTuning.index(e.getKey())]=e.getValue();
        return values;
    }
    static float[] camera(Variant v,float[] own){
        float[] values=v.base()==Base.OWN?own.clone():siliconshaders.metal.camera.CameraSettings.defaults();
        for(var e:v.camera().entrySet())values[siliconshaders.metal.camera.CameraSettings.index(e.getKey())]=e.getValue();return values;
    }
    static siliconshaders.metal.sun.SunLook.Values look(Variant v,siliconshaders.metal.sun.SunLook.Values own){
        var values=v.base()==Base.OWN?own:siliconshaders.metal.sun.SunLook.Values.NEUTRAL;
        for(var e:v.look().entrySet())values=values.with(e.getKey(),e.getValue());return values;
    }
    /** A copy of a settings record with some fields replaced (record components by name). */
    static SunSettings.State with(SunSettings.State state,Map<String,Object> changes) {
        if(changes.isEmpty())return state;
        try {
            RecordComponent[] parts=SunSettings.State.class.getRecordComponents();
            Object[] values=new Object[parts.length];Class<?>[] types=new Class<?>[parts.length];int used=0;
            for(int i=0;i<parts.length;i++) {
                types[i]=parts[i].getType();values[i]=parts[i].getAccessor().invoke(state);
                Object next=changes.get(parts[i].getName());
                if(next!=null) {
                    if(types[i]==int.class&&!(next instanceof Integer)||types[i]==boolean.class&&!(next instanceof Boolean))
                        throw new IllegalArgumentException("Wrong type for "+parts[i].getName());
                    values[i]=next;used++;
                }
            }
            if(used!=changes.size())throw new IllegalArgumentException("Unknown shader setting in "+changes.keySet());
            SunSettings.State next=SunSettings.State.class.getDeclaredConstructor(types).newInstance(values);
            if(!SunSettings.validSize(next.shadowSize())||next.shadowDistance()<32||next.shadowDistance()>256||next.shadowDistance()%16!=0
                ||next.shadowLevels()<2||next.shadowLevels()>4)throw new IllegalArgumentException("Shader setting out of range: "+changes);
            return next;
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
