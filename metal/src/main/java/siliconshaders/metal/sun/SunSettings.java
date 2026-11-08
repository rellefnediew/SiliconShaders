package siliconshaders.metal.sun;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/** Only the small native shader's controls; independent of shaderpack support. */
public final class SunSettings {
    public record State(boolean enabled,int shadowSize,boolean smooth,int shadowDistance,int shadowLevels,boolean shadowCulling,boolean filterOptimization,boolean casterSelection,boolean receiverScan,boolean memoryOptimization,boolean shadowMapOptimization,boolean geometryOptimization,boolean farShadowCache,boolean gbuffer,int gbufferView) {
        public State(boolean enabled,int shadowSize,boolean smooth) { this(enabled,shadowSize,smooth,64,3,true,true,true,true,true,true,true,true,true,0); }
        /** A copy with some settings changed. */
        public State with(java.util.function.Consumer<Editor> change) { Editor editor=new Editor(this);change.accept(editor);return editor.state(); }
    }
    /** A State while it changes. A new setting is added here and in the record, not at every place that changes one. */
    public static final class Editor {
        public boolean enabled;
        public int shadowSize;
        public boolean smooth;
        public int shadowDistance;
        public int shadowLevels;
        public boolean shadowCulling;
        public boolean filterOptimization;
        public boolean casterSelection;
        public boolean receiverScan;
        public boolean memoryOptimization;
        public boolean shadowMapOptimization;
        public boolean geometryOptimization;
        public boolean farShadowCache;
        public boolean gbuffer;
        public int gbufferView;
        private Editor(State s) { enabled=s.enabled();shadowSize=s.shadowSize();smooth=s.smooth();shadowDistance=s.shadowDistance();shadowLevels=s.shadowLevels();shadowCulling=s.shadowCulling();filterOptimization=s.filterOptimization();casterSelection=s.casterSelection();receiverScan=s.receiverScan();memoryOptimization=s.memoryOptimization();shadowMapOptimization=s.shadowMapOptimization();geometryOptimization=s.geometryOptimization();farShadowCache=s.farShadowCache();gbuffer=s.gbuffer();gbufferView=s.gbufferView(); }
        private State state() { return new State(enabled,shadowSize,smooth,shadowDistance,shadowLevels,shadowCulling,filterOptimization,casterSelection,receiverScan,memoryOptimization,shadowMapOptimization,geometryOptimization,farShadowCache,gbuffer,gbufferView); }
    }
    private static final Gson JSON=new Gson();
    public static final int[] SIZES={0,512,1024,2048,4096};
    /** 0 off, 1 albedo, 2 light, 3 normals, 4 material, 5 vanilla lighting rebuilt from the G-buffer (mcsun_gbuffer.inc). */
    public static final int GBUFFER_VIEWS=6;
    private static class Holder { static final SunSettings VALUE=new SunSettings(FabricLoader.getInstance().getGameDir()); }
    public static SunSettings get() { return Holder.VALUE; }
    private final Path file;
    private volatile State state=new State(false,1024,true);
    private volatile String issue="";
    public SunSettings(Path directory) {
        siliconshaders.metal.BrandMigration.settings(directory);
        file=directory.resolve("config/siliconshaders-sun.json");
        try {
            if(Files.isRegularFile(file)) {
                if(Files.size(file)>4096) throw new IllegalArgumentException("Oversized sun settings");
                var object=JSON.fromJson(Files.readString(file),com.google.gson.JsonObject.class);
                if(object==null) throw new IllegalArgumentException("Invalid shadow resolution");
                // Files written before a setting existed read it as its default.
                if(!object.has("shadowDistance"))object.addProperty("shadowDistance",64);
                if(!object.has("shadowLevels"))object.addProperty("shadowLevels",3);
                if(!object.has("gbuffer")){object.addProperty("gbuffer",true);object.addProperty("gbufferView",0);}
                for(String on:new String[]{"smooth","shadowCulling","filterOptimization","casterSelection","receiverScan","memoryOptimization","shadowMapOptimization","geometryOptimization","farShadowCache"})
                    if(!object.has(on))object.addProperty(on,true);
                State read=JSON.fromJson(object,State.class);
                if(read==null || !validSize(read.shadowSize())) throw new IllegalArgumentException("Invalid shadow resolution");
                if(read.shadowDistance()<32||read.shadowDistance()>256||read.shadowDistance()%16!=0)throw new IllegalArgumentException("Invalid shadow distance");
                if(read.shadowLevels()<2||read.shadowLevels()>4)throw new IllegalArgumentException("Invalid shadow levels");
                if(read.gbufferView()<0||read.gbufferView()>GBUFFER_VIEWS-1)throw new IllegalArgumentException("Invalid G-buffer view");
                state=read;
            }
        } catch(Exception failure) { issue=failure.getMessage(); }
    }
    public static boolean validSize(int size) { for(int s:SIZES) if(s==size) return true;return false; }
    public State state() { return state; }
    /** Benchmark only (bench.ShaderBench): applies a state for this session without writing config/siliconshaders-sun.json. */
    public synchronized void benchmark(State next) { state=next; }
    /** Live menu state; the menu commits all four settings files in one rollback-capable transaction. */
    public synchronized void preview(State next){
        if(next==null||!validSize(next.shadowSize())||next.shadowDistance()<32||next.shadowDistance()>256||next.shadowDistance()%16!=0||next.shadowLevels()<2||next.shadowLevels()>4||next.gbufferView()<0||next.gbufferView()>=GBUFFER_VIEWS)throw new IllegalArgumentException("Invalid shadow settings");
        state=next;
    }
    public String issue() { return issue; }
    public synchronized void enabled(boolean enabled) { save(state.with(e->e.enabled=enabled)); }
    public synchronized void smooth(boolean smooth) { save(state.with(e->e.smooth=smooth)); }
    public synchronized void nextSize() {
        int next=0;for(int i=0;i<SIZES.length;i++) if(SIZES[i]==state.shadowSize()) next=SIZES[(i+1)%SIZES.length];
        int size=next;save(state.with(e->e.shadowSize=size));
    }
    public synchronized void distance(int blocks) {
        if(blocks<32||blocks>256||blocks%16!=0)throw new IllegalArgumentException("Shadow distance must be 32–256 in steps of 16");
        save(state.with(e->e.shadowDistance=blocks));
    }
    public synchronized void nextLevels() { save(state.with(e->e.shadowLevels=e.shadowLevels==4?2:e.shadowLevels+1)); }
    public synchronized void culling(boolean enabled) { save(state.with(e->e.shadowCulling=enabled)); }
    public synchronized void filterOptimization(boolean enabled) { save(state.with(e->e.filterOptimization=enabled)); }
    public synchronized void casterSelection(boolean enabled) { save(state.with(e->e.casterSelection=enabled)); }
    public synchronized void receiverScan(boolean enabled) { save(state.with(e->e.receiverScan=enabled)); }
    public synchronized void memoryOptimization(boolean enabled) { save(state.with(e->e.memoryOptimization=enabled)); }
    public synchronized void shadowMapOptimization(boolean enabled) { save(state.with(e->e.shadowMapOptimization=enabled)); }
    public synchronized void geometryOptimization(boolean enabled) { save(state.with(e->e.geometryOptimization=enabled)); }
    public synchronized void farShadowCache(boolean enabled) { save(state.with(e->e.farShadowCache=enabled)); }
    public synchronized void gbuffer(boolean enabled) { save(state.with(e->e.gbuffer=enabled)); }
    private void save(State next) {
        try {
            Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"sun-",".tmp");
            try { Files.writeString(temporary,JSON.toJson(next)+"\n");Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(temporary); }
            state=next;issue="";
        } catch(Exception failure) { issue=failure.getMessage(); }
    }
    public synchronized void failed(Throwable failure) {
        state=state.with(e->e.enabled=false);issue=String.valueOf(failure.getMessage());
        siliconshaders.metal.Log.warn("[SiliconShaders sun] Disabled: "+issue);
    }
}
