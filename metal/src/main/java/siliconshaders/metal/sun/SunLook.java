package siliconshaders.metal.sun;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/**
 * The native lighting model's look, kept apart from the shader's switches: exposure, saturation, contrast and the color
 * grading (white balance, vibrance, tone curve and white point, lift / gamma / gain, automatic exposure). Every neutral
 * value reproduces the earlier look exactly; a file written before a setting existed reads it as neutral.
 */
public final class SunLook {
    public record Param(String key,float neutral,float min,float max,float step){}
    private static final float C=0.01f;  // color channel step, as SunTuning's
    public static final float EXPOSURE_MIN=0.25f,EXPOSURE_MAX=4,SATURATION_MAX=2,CONTRAST_MIN=0.5f,CONTRAST_MAX=2;
    /** Tone curves (sun.metal sunToneCurve): the standard luminance curve, then per-channel ACES, AgX and Khronos Neutral. */
    public static final int CURVES=4;
    public static final List<Param> PARAMS=List.of(
        new Param("exposure",1,EXPOSURE_MIN,EXPOSURE_MAX,.05f),new Param("saturation",1,0,SATURATION_MAX,.05f),new Param("contrast",1,CONTRAST_MIN,CONTRAST_MAX,.05f),
        new Param("temperature",0,-1,1,.05f),new Param("tint",0,-1,1,.05f),new Param("vibrance",0,-1,1,.05f),
        new Param("curve",0,0,CURVES-1,1),new Param("whitePoint",7.25f,1,32,.25f),
        new Param("autoExposure",0,0,1,.05f),new Param("autoExposureSpeed",1,.1f,4,.1f),new Param("autoExposureRange",2,.5f,4,.25f),
        new Param("shadows",0,-.25f,.25f,.01f),new Param("shadowTintR",1,0,1,C),new Param("shadowTintG",1,0,1,C),new Param("shadowTintB",1,0,1,C),
        new Param("midtones",1,.5f,2,.05f),new Param("midtoneTintR",1,0,1,C),new Param("midtoneTintG",1,0,1,C),new Param("midtoneTintB",1,0,1,C),
        new Param("highlights",1,.5f,2,.05f),new Param("highlightTintR",1,0,1,C),new Param("highlightTintG",1,0,1,C),new Param("highlightTintB",1,0,1,C));
    private static final Map<String,Integer> INDEX=new LinkedHashMap<>();
    static { for(int i=0;i<PARAMS.size();i++)INDEX.put(PARAMS.get(i).key(),i); }
    public static int index(String key){Integer i=INDEX.get(key);if(i==null)throw new IllegalArgumentException("Unknown look setting "+key);return i;}

    public static final class Values {
        public static final Values NEUTRAL;
        static { float[] v=new float[PARAMS.size()];for(int i=0;i<v.length;i++)v[i]=PARAMS.get(i).neutral();NEUTRAL=new Values(v); }
        private final float[] v;
        public Values(float[] values){v=values.clone();}
        public Values(float exposure,float saturation,float contrast){this(with(with(with(NEUTRAL.v,0,exposure),1,saturation),2,contrast));}
        private static float[] with(float[] values,int i,float value){float[] next=values.clone();next[i]=value;return next;}
        public float get(String key){return v[index(key)];}
        public Values with(String key,float value){return new Values(with(v,index(key),value));}
        public float exposure(){return v[0];}
        public float saturation(){return v[1];}
        public float contrast(){return v[2];}
        boolean valid() {
            if(v.length!=PARAMS.size())return false;
            for(int i=0;i<v.length;i++){var p=PARAMS.get(i);if(!Float.isFinite(v[i])||v[i]<p.min()||v[i]>p.max())return false;}
            return true;
        }
        JsonObject json(){JsonObject o=new JsonObject();for(int i=0;i<v.length;i++)o.addProperty(PARAMS.get(i).key(),v[i]);return o;}
        static Values read(JsonObject o){
            float[] v=NEUTRAL.v.clone();
            for(int i=0;i<v.length;i++){var e=o.get(PARAMS.get(i).key());if(e!=null&&e.isJsonPrimitive())v[i]=e.getAsFloat();}
            return new Values(v);
        }
    }
    private static final Gson JSON=new Gson();
    private static class Holder { static final SunLook VALUE=new SunLook(FabricLoader.getInstance().getGameDir()); }
    public static SunLook get() { return Holder.VALUE; }
    private final Path file;
    private volatile Values values=Values.NEUTRAL;
    public SunLook(Path directory) {
        siliconshaders.metal.BrandMigration.settings(directory);
        file=directory.resolve("config/siliconshaders-sun-look.json");
        try {
            if(Files.isRegularFile(file)) {
                if(Files.size(file)>4096)throw new IllegalArgumentException("Oversized sun look");
                Values read=Values.read(JsonParser.parseString(Files.readString(file)).getAsJsonObject());
                if(read.valid())values=read;
            }
        } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders sun] Look settings ignored: "+failure.getMessage()); }
    }
    /** Validated live menu state; persisted together with the other graphics settings. */
    public synchronized void preview(Values next){if(next==null||!next.valid())throw new IllegalArgumentException("Invalid look settings");values=next;}
    public Values values() { return values; }
    private Values gradeOf;private final float[] gradeCache=new float[16];
    /** The grading values of the current look, recomputed only when the look changes (render thread). */
    public float[] grade(){Values v=values;if(v!=gradeOf){grade(v,gradeCache);gradeOf=v;}return gradeCache;}
    public String json() { return JSON.toJson(values.json()); }
    public synchronized void exposure(float value) { save(values.with("exposure",value)); }
    public synchronized void saturation(float value) { save(values.with("saturation",value)); }
    public synchronized void contrast(float value) { save(values.with("contrast",value)); }
    public synchronized void reset() { save(Values.NEUTRAL); }
    private void save(Values next) {
        if(!next.valid())return;
        values=next;
        try {
            Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"sun-look-",".tmp");
            try { Files.writeString(temporary,json()+"\n");Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(temporary); }
        } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders sun] Look settings not saved: "+failure.getMessage()); }
    }

    /**
     * The 16 grading values of the lighting packet (sun.metal SunLighting.grade): white balance and a slot native code
     * fills with the automatic exposure factor, lift and vibrance, gamma and the curve, gain and the curve's white scale.
     */
    public static void grade(Values look,float[] out) {
        float temperature=look.get("temperature"),tint=look.get("tint");
        // White balance: the image times a black body's color relative to 6500 K (warmer: lower temperature, up to
        // about 3700 K; cooler up to about 11300 K), green against magenta, luminance kept.
        float[] balance=blackBody(6500/(float)Math.pow(2,temperature*.8f)),reference=blackBody(6500);
        for(int i=0;i<3;i++)balance[i]/=reference[i];
        balance[1]*=1-.2f*tint;
        float luma=luma(balance);for(int i=0;i<3;i++)out[i]=balance[i]/luma;
        out[3]=1;
        float[] lift=tint(look,"shadowTint"),gamma=tint(look,"midtoneTint"),gain=tint(look,"highlightTint");
        for(int i=0;i<3;i++){
            out[4+i]=look.get("shadows")+(lift[i]-1)*.1f;
            out[8+i]=Math.clamp(look.get("midtones")*(1+(gamma[i]-1)*.25f),.2f,5);
            out[12+i]=Math.max(look.get("highlights")*(1+(gain[i]-1)*.25f),0);
        }
        out[7]=look.get("vibrance");
        int curve=Math.round(look.get("curve"));out[11]=curve;
        out[15]=1/curveGrey(curve,look.get("whitePoint"));
    }
    // A picked color as a multiplier with luminance 1; white (or black) is neutral.
    private static float[] tint(Values look,String stem){
        float[] c={look.get(stem+"R"),look.get(stem+"G"),look.get(stem+"B")};float luma=luma(c);
        if(luma<1e-3f)return new float[]{1,1,1};
        for(int i=0;i<3;i++)c[i]/=luma;return c;
    }
    private static float luma(float[] c){return .2126f*c[0]+.7152f*c[1]+.0722f*c[2];}
    // Tanner Helland's black body fit, as linear light.
    private static float[] blackBody(float kelvin){
        double t=kelvin/100,r,g,b;
        if(t<=66){r=255;g=99.4708025861*Math.log(t)-161.1195681661;}else{r=329.698727446*Math.pow(t-60,-.1332047592);g=288.1221695283*Math.pow(t-60,-.0755148492);}
        b=t>=66?255:t<=19?0:138.5177312231*Math.log(t-10)-305.0447927307;
        double[] c={r,g,b};float[] out=new float[3];
        for(int i=0;i<3;i++)out[i]=(float)Math.pow(Math.clamp(c[i],1,255)/255,2.2);
        return out;
    }
    /** A grey's display value through the tone curve (sun.metal sunToneCurve without the white scale). */
    static float curveGrey(int curve,float x){
        switch(curve){
            case 1->{double v=x*2.0;return (float)((v*(v+.0245786)-.000090537)/(v*(.983729*v+.4329510)+.238081));}
            case 2->{
                double v=x*1.33;double[] in={v*(.842479062253094+.0784335999999992+.0792237451477643),v*(.0423282422610123+.878468636469772+.0791661274605434),
                    v*(.0423756549057051+.0784336+.879142973793104)},y=new double[3];
                for(int i=0;i<3;i++){double e=(Math.clamp(Math.log(Math.max(in[i],1e-10))/Math.log(2),-12.47393,4.026069)+12.47393)/16.499999,e2=e*e,e4=e2*e2;
                    y[i]=15.5*e4*e2-40.14*e4*e+31.96*e4-6.868*e2*e+.4298*e2+.1191*e-.00232;}
                double g=-.0528968517574562*y[0]+1.15190312990417*y[1]-.0989611768448433*y[2];
                return (float)Math.pow(Math.max(g,0),2.2);
            }
            case 3->{
                double v=x*1.705,offset=v<.08?v-6.25*v*v:.04;v-=offset;
                return (float)(v<.76?v:1-.0576/(v-.52));
            }
            default->{return (float)((x*(2.51*x+.03))/(x*(2.43*x+.59)+.14));}
        }
    }
}
