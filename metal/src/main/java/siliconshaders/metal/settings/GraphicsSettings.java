package siliconshaders.metal.settings;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import siliconshaders.metal.sun.*;
import siliconshaders.metal.camera.CameraSettings;
import net.fabricmc.loader.api.FabricLoader;

/** A menu transaction over the existing settings stores. Preview stays in memory; Done batches disk writes. */
public final class GraphicsSettings {
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private final SunSettings shadows;private final SunTuning sun;private final CameraSettings camera;private final SunLook look;private final Path directory;
    public static GraphicsSettings live(){return new GraphicsSettings(FabricLoader.getInstance().getGameDir(),SunSettings.get(),SunTuning.get(),CameraSettings.get(),SunLook.get());}
    public GraphicsSettings(Path directory){this(directory,new SunSettings(directory),new SunTuning(directory),new CameraSettings(directory),new SunLook(directory));}
    private GraphicsSettings(Path directory,SunSettings shadows,SunTuning sun,CameraSettings camera,SunLook look){this.directory=directory;this.shadows=shadows;this.sun=sun;this.camera=camera;this.look=look;}
    public Path directory(){return directory;}
    public Map<String,Float> capture(){
        Map<String,Float> out=new LinkedHashMap<>();float[] tuning=sun.values(),effects=camera.snapshot();
        for(int i=0;i<tuning.length;i++)if(SunTuning.PARAMS.get(i).max()>SunTuning.PARAMS.get(i).min())out.put("sun."+SunTuning.PARAMS.get(i).key(),tuning[i]);
        for(int i=0;i<effects.length;i++)out.put("camera."+CameraSettings.PARAMS.get(i).key(),effects[i]);
        var l=look.values();for(var p:SunLook.PARAMS)out.put("look."+p.key(),l.get(p.key()));
        var s=shadows.state();out.put("shadow.enabled",s.enabled()?1f:0f);out.put("shadow.size",(float)s.shadowSize());out.put("shadow.smooth",s.smooth()?1f:0f);out.put("shadow.distance",(float)s.shadowDistance());out.put("shadow.levels",(float)s.shadowLevels());
        out.put("shadow.shadowCulling",s.shadowCulling()?1f:0f);out.put("shadow.filterOptimization",s.filterOptimization()?1f:0f);out.put("shadow.casterSelection",s.casterSelection()?1f:0f);out.put("shadow.receiverScan",s.receiverScan()?1f:0f);out.put("shadow.memoryOptimization",s.memoryOptimization()?1f:0f);out.put("shadow.shadowMapOptimization",s.shadowMapOptimization()?1f:0f);out.put("shadow.geometryOptimization",s.geometryOptimization()?1f:0f);out.put("shadow.farShadowCache",s.farShadowCache()?1f:0f);out.put("shadow.gbuffer",s.gbuffer()?1f:0f);out.put("shadow.gbufferView",(float)s.gbufferView());return out;
    }
    private float value(Map<String,Float> values,String key){return values.getOrDefault(key,GraphicsCatalog.RULES.get(key).neutral());}
    private boolean on(Map<String,Float> values,String key){return value(values,key)>.5f;}
    public void preview(Map<String,Float> values){
        GraphicsCatalog.validate(values);
        float[] tuning=sun.values(),effects=camera.snapshot();
        for(int i=0;i<tuning.length;i++){Float v=values.get("sun."+SunTuning.PARAMS.get(i).key());if(v!=null)tuning[i]=v;}
        for(int i=0;i<effects.length;i++){Float v=values.get("camera."+CameraSettings.PARAMS.get(i).key());if(v!=null)effects[i]=v;}
        float[] lookValues=new float[SunLook.PARAMS.size()];for(int i=0;i<lookValues.length;i++)lookValues[i]=value(values,"look."+SunLook.PARAMS.get(i).key());var nextLook=new SunLook.Values(lookValues);
        var nextShadow=new SunSettings.State(on(values,"shadow.enabled"),Math.round(value(values,"shadow.size")),on(values,"shadow.smooth"),Math.round(value(values,"shadow.distance")),Math.round(value(values,"shadow.levels")),on(values,"shadow.shadowCulling"),on(values,"shadow.filterOptimization"),on(values,"shadow.casterSelection"),on(values,"shadow.receiverScan"),on(values,"shadow.memoryOptimization"),on(values,"shadow.shadowMapOptimization"),on(values,"shadow.geometryOptimization"),on(values,"shadow.farShadowCache"),on(values,"shadow.gbuffer"),Math.round(value(values,"shadow.gbufferView")));
        // All values have been validated before any store is published; this runs on the render/menu thread.
        shadows.preview(nextShadow);look.preview(nextLook);sun.preview(tuning);camera.preview(effects);
    }
    public void commit(Map<String,Float> values) throws IOException {commit(values,Map.of());}
    public void commit(Map<String,Float> values,Map<Path,String> extra) throws IOException {
        preview(values);Map<Path,String> files=new LinkedHashMap<>();JsonObject tuning=new JsonObject(),effects=new JsonObject();
        for(var p:SunTuning.PARAMS)if(p.max()>p.min())tuning.addProperty(p.key(),sun.value(SunTuning.index(p.key())));
        for(var p:CameraSettings.PARAMS)effects.addProperty(p.key(),camera.value(CameraSettings.index(p.key())));
        files.put(directory.resolve("config/siliconshaders-sun-tuning.json"),JSON.toJson(tuning)+"\n");files.put(directory.resolve("config/siliconshaders-camera.json"),JSON.toJson(effects)+"\n");
        files.put(directory.resolve("config/siliconshaders-sun-look.json"),look.json()+"\n");files.put(directory.resolve("config/siliconshaders-sun.json"),JSON.toJson(shadows.state())+"\n");files.putAll(extra);SettingsFiles.write(files);
    }
}
