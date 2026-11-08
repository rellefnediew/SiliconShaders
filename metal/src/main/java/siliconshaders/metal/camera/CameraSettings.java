package siliconshaders.metal.camera;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;

/** Persisted camera controls; independent of the lighting and water packet layouts. */
public final class CameraSettings {
    public enum Group { GENERAL, BLOOM, DOF, MOTION, FLARE, CHROMA, VIGNETTE, SHARPEN, GODRAYS }
    public record Param(String key,Group group,float neutral,float min,float max,float step) {
        public boolean toggle(){return !key.equals("raysVariant")&&!key.equals("bloomVariant")&&min==0&&max==1&&step==1;}
        public float clamp(float v){return Math.clamp(Math.round(v/step)*step,min,max);}
    }
    public static final java.util.List<Param> PARAMS=java.util.List.of(
        new Param("enabled",Group.GENERAL,1.0f,0.0f,1.0f,1.0f),
        new Param("bloom",Group.BLOOM,0.0f,0.0f,1.0f,1.0f),
        new Param("bloomStrength",Group.BLOOM,0.2f,0.0f,2.0f,0.01f),
        new Param("bloomThreshold",Group.BLOOM,0.7f,0.0f,2.0f,0.01f),
        new Param("bloomKnee",Group.BLOOM,0.2f,0.01f,1.0f,0.01f),
        new Param("bloomRadius",Group.BLOOM,1.0f,0.25f,3.0f,0.05f),
        new Param("bloomLevels",Group.BLOOM,3.0f,1.0f,5.0f,1.0f),
        new Param("bloomScale",Group.BLOOM,0.5f,0.25f,0.5f,0.25f),
        new Param("dof",Group.DOF,0.0f,0.0f,1.0f,1.0f),
        new Param("autoFocus",Group.DOF,1.0f,0.0f,1.0f,1.0f),
        new Param("focusDistance",Group.DOF,8.0f,0.25f,256.0f,0.25f),
        new Param("focusRange",Group.DOF,4.0f,0.25f,64.0f,0.25f),
        new Param("dofRadius",Group.DOF,8.0f,0.0f,32.0f,0.5f),
        new Param("dofNear",Group.DOF,1.0f,0.0f,2.0f,0.05f),
        new Param("dofFar",Group.DOF,1.0f,0.0f,2.0f,0.05f),
        new Param("dofSamples",Group.DOF,12.0f,4.0f,24.0f,1.0f),
        new Param("focusSpeed",Group.DOF,6.0f,0.25f,20.0f,0.25f),
        new Param("motion",Group.MOTION,0.0f,0.0f,1.0f,1.0f),
        new Param("motionStrength",Group.MOTION,1.0f,0.0f,2.0f,0.05f),
        new Param("shutterMs",Group.MOTION,8.0f,0.0f,32.0f,0.5f),
        new Param("motionMax",Group.MOTION,32.0f,0.0f,96.0f,1.0f),
        new Param("motionSamples",Group.MOTION,12.0f,4.0f,24.0f,1.0f),
        new Param("flare",Group.FLARE,0.0f,0.0f,1.0f,1.0f),
        new Param("flareStrength",Group.FLARE,0.3f,0.0f,2.0f,0.01f),
        new Param("flareGhosts",Group.FLARE,5.0f,1.0f,8.0f,1.0f),
        new Param("flareSpacing",Group.FLARE,0.4f,0.05f,1.0f,0.01f),
        new Param("flareSize",Group.FLARE,0.08f,0.01f,0.3f,0.005f),
        new Param("flareHalo",Group.FLARE,0.2f,0.0f,2.0f,0.01f),
        new Param("flareStreak",Group.FLARE,0.2f,0.0f,2.0f,0.01f),
        new Param("flareSpeed",Group.FLARE,8.0f,0.5f,30.0f,0.5f),
        new Param("flareMoon",Group.FLARE,1.0f,0.0f,1.0f,1.0f),
        new Param("flareAngle",Group.FLARE,0.0f,-90.0f,90.0f,1.0f),
        new Param("chroma",Group.CHROMA,0.0f,0.0f,1.0f,1.0f),
        new Param("chromaStrength",Group.CHROMA,3.0f,0.0f,16.0f,0.1f),
        new Param("chromaStart",Group.CHROMA,0.2f,0.0f,0.95f,0.05f),
        new Param("chromaFalloff",Group.CHROMA,2.0f,0.5f,6.0f,0.1f),
        new Param("vignette",Group.VIGNETTE,0.0f,0.0f,1.0f,1.0f),
        new Param("vignetteStrength",Group.VIGNETTE,0.3f,0.0f,1.0f,0.01f),
        new Param("vignetteRadius",Group.VIGNETTE,0.75f,0.1f,1.5f,0.01f),
        new Param("vignetteSoftness",Group.VIGNETTE,0.4f,0.01f,1.0f,0.01f),
        new Param("vignetteRoundness",Group.VIGNETTE,1.0f,0.0f,1.0f,0.05f),
        new Param("vignetteR",Group.VIGNETTE,0.0f,0.0f,1.0f,0.01f),
        new Param("vignetteG",Group.VIGNETTE,0.0f,0.0f,1.0f,0.01f),
        new Param("vignetteB",Group.VIGNETTE,0.0f,0.0f,1.0f,0.01f),
        new Param("sharpen",Group.SHARPEN,0.0f,0.0f,1.0f,1.0f),
        new Param("sharpenStrength",Group.SHARPEN,0.3f,0.0f,2.0f,0.01f),
        new Param("sharpenRadius",Group.SHARPEN,1.0f,0.5f,2.0f,0.1f),
        new Param("sharpenClamp",Group.SHARPEN,0.1f,0.0f,0.5f,0.01f),
        new Param("godrays",Group.GODRAYS,0,0,1,1),
        new Param("raysStrength",Group.GODRAYS,0.35f,0,2,0.01f),
        new Param("raysDensity",Group.GODRAYS,0.015f,0.001f,0.08f,0.001f),
        new Param("raysDistance",Group.GODRAYS,64,16,192,4),
        new Param("raysSamples",Group.GODRAYS,16,8,64,4),
        new Param("raysScale",Group.GODRAYS,0.25f,0.25f,0.5f,0.25f),
        new Param("raysAnisotropy",Group.GODRAYS,0.65f,0,0.85f,0.05f),
        new Param("raysMoon",Group.GODRAYS,0.3f,0,2,0.05f),
        new Param("raysUnderwater",Group.GODRAYS,1,0,3,0.05f),
        new Param("raysVariant",Group.GODRAYS,0,0,1,1),
        new Param("bloomVariant",Group.BLOOM,0,0,1,1));
    public static final int COUNT=PARAMS.size();
    public static final int ENABLED=index("enabled"),BLOOM=index("bloom"),BLOOM_STRENGTH=index("bloomStrength"),BLOOM_VARIANT=index("bloomVariant"),
        DOF=index("dof"),DOF_RADIUS=index("dofRadius"),DOF_NEAR=index("dofNear"),DOF_FAR=index("dofFar"),
        MOTION=index("motion"),MOTION_STRENGTH=index("motionStrength"),SHUTTER=index("shutterMs"),MOTION_MAX=index("motionMax"),
        FLARE=index("flare"),FLARE_STRENGTH=index("flareStrength"),FLARE_MOON=index("flareMoon"),CHROMA=index("chroma"),CHROMA_STRENGTH=index("chromaStrength"),
        VIGNETTE=index("vignette"),VIGNETTE_STRENGTH=index("vignetteStrength"),SHARPEN=index("sharpen"),SHARPEN_STRENGTH=index("sharpenStrength"),SHARPEN_CLAMP=index("sharpenClamp"),
        GODRAYS=index("godrays"),RAYS_STRENGTH=index("raysStrength");
    public static boolean hdrBloom(float[] values){return values[ENABLED]>.5f&&values[BLOOM]>.5f&&values[BLOOM_STRENGTH]>0&&values[BLOOM_VARIANT]>.5f;}
    public boolean hdrBloom(){return hdrBloom(values);}
    private static final Gson JSON=new GsonBuilder().setPrettyPrinting().create();
    private static final class Holder { static final CameraSettings INSTANCE=new CameraSettings(FabricLoader.getInstance().getGameDir()); }
    public static CameraSettings get(){return Holder.INSTANCE;}
    private final Path file;
    private volatile float[] values=defaults();
    private volatile String issue="";
    public CameraSettings(Path directory){
        siliconshaders.metal.BrandMigration.settings(directory);
        file=directory.resolve("config/siliconshaders-camera.json");
        try {
            if(Files.isRegularFile(file)){
                if(Files.size(file)>16384)throw new IllegalArgumentException("Oversized camera settings");
                JsonObject object=JSON.fromJson(Files.readString(file),JsonObject.class);
                if(object==null)throw new IllegalArgumentException("Empty camera settings");
                float[] read=defaults();
                for(int i=0;i<COUNT;i++){
                    Param p=PARAMS.get(i);JsonElement e=object.get(p.key());
                    if(e==null)continue;float v=p.toggle()&&e.isJsonPrimitive()&&e.getAsJsonPrimitive().isBoolean()?(e.getAsBoolean()?1:0):e.getAsFloat();
                    if(!Float.isFinite(v))throw new IllegalArgumentException("Invalid camera setting: "+p.key());
                    read[i]=p.clamp(v);
                }
                values=read;
            }
        }catch(Exception failure){issue=String.valueOf(failure.getMessage());siliconshaders.metal.Log.warn("[SiliconShaders camera] Settings ignored: "+issue);}
    }
    public static float[] defaults(){float[] result=new float[COUNT];for(int i=0;i<COUNT;i++)result[i]=PARAMS.get(i).clamp(PARAMS.get(i).neutral());return result;}
    public static int index(String key){for(int i=0;i<COUNT;i++)if(PARAMS.get(i).key().equals(key))return i;throw new IllegalArgumentException(key);}
    public float value(int index){return values[index];}
    public float[] snapshot(){return values.clone();}
    float[] published(){return values;}
    public String issue(){return issue;}
    /** Temporary benchmark state, never writes the player settings file. */
    public synchronized void benchmark(float[] input){
        if(input==null||input.length!=COUNT)throw new IllegalArgumentException("Camera benchmark layout");
        float[] next=input.clone();for(int i=0;i<COUNT;i++){if(!Float.isFinite(next[i]))throw new IllegalArgumentException("Nonfinite camera benchmark setting");next[i]=PARAMS.get(i).clamp(next[i]);}values=next;
    }
    /** Live menu state, without a settings-file write for every slider movement. */
    public synchronized void preview(float[] input){benchmark(input);}
    public java.util.Map<String,Float> describe(){var result=new LinkedHashMap<String,Float>();float[] current=values;for(int i=0;i<COUNT;i++)result.put(PARAMS.get(i).key(),current[i]);return result;}
    public synchronized void set(int i,float value){if(i<0||i>=COUNT||!Float.isFinite(value))return;float[] next=values.clone();next[i]=PARAMS.get(i).clamp(value);save(next);}
    public synchronized void reset(Group group){float[] next=values.clone();for(int i=0;i<COUNT;i++)if(group==null||PARAMS.get(i).group()==group)next[i]=PARAMS.get(i).clamp(PARAMS.get(i).neutral());save(next);}
    private void save(float[] next){
        try {
            Files.createDirectories(file.getParent());Path temp=Files.createTempFile(file.getParent(),"camera-",".tmp");
            try {Map<String,Float> data=new LinkedHashMap<>();for(int i=0;i<COUNT;i++)data.put(PARAMS.get(i).key(),next[i]);Files.writeString(temp,JSON.toJson(data)+"\n");Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            finally {Files.deleteIfExists(temp);}
            values=next;issue="";
        }catch(Exception failure){issue=String.valueOf(failure.getMessage());siliconshaders.metal.Log.warn("[SiliconShaders camera] Settings not saved: "+issue);}
    }
    public int mask(){return mask(values);}
    public static int mask(float[] v){
        if(v[ENABLED]<.5f)return 0;int mask=0;
        if(v[BLOOM]>.5f&&v[BLOOM_STRENGTH]>0)mask|=1;
        if(v[DOF]>.5f&&v[DOF_RADIUS]>0&&(v[DOF_NEAR]>0||v[DOF_FAR]>0))mask|=2;
        if(v[MOTION]>.5f&&v[MOTION_STRENGTH]>0&&v[SHUTTER]>0&&v[MOTION_MAX]>0)mask|=4;
        if(v[FLARE]>.5f&&v[FLARE_STRENGTH]>0)mask|=8;
        if(v[CHROMA]>.5f&&v[CHROMA_STRENGTH]>0)mask|=16;
        if(v[VIGNETTE]>.5f&&v[VIGNETTE_STRENGTH]>0)mask|=32;
        if(v[SHARPEN]>.5f&&v[SHARPEN_STRENGTH]>0&&v[SHARPEN_CLAMP]>0)mask|=64;
        if(v[GODRAYS]>.5f&&v[RAYS_STRENGTH]>0)mask|=128;
        return mask;
    }
}
