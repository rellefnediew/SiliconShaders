package siliconshaders.metal.pack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.loader.api.FabricLoader;

/** Main-thread settings and background compilation publish immutable snapshots to the renderer. */
public final class ShaderManager {
    public enum Phase { OFF, COMPILING, READY, ACTIVE, ERROR, NEEDS_METAL, OVERWORLD_ONLY }
    public record Snapshot(long revision, boolean enabled, String pack, Phase phase, String detail, PackProgram program) { }
    private static class Holder { static final ShaderManager INSTANCE=new ShaderManager(FabricLoader.getInstance().getGameDir()); }
    public static ShaderManager get() { return Holder.INSTANCE; }
    public final Path gameDir;
    private final ExecutorService compiler=Executors.newSingleThreadExecutor(r -> { Thread t=new Thread(r,"siliconshaders shader compiler"); t.setDaemon(true); return t; });
    @FunctionalInterface interface Compiler { PackProgram compile(Path directory,String name,boolean reload) throws Exception; }
    private final Compiler compile;
    private volatile Snapshot state=new Snapshot(0,false,"",Phase.OFF,"",null);

    ShaderManager(Path gameDir) { this(gameDir,PackProgram::compile); }
    ShaderManager(Path gameDir,Compiler compile) {
        this.gameDir=gameDir;
        this.compile=compile;
        try {
            ShaderSettings saved=ShaderSettings.load(gameDir);
            request(saved.enabled(),saved.pack(),false,false);
        } catch (IOException e) { state=new Snapshot(1,false,"",Phase.ERROR,e.getMessage(),null); }
    }
    public Snapshot snapshot() { return state; }

    public List<String> packs() throws IOException {
        Path directory=gameDir.resolve("shaderpacks"); Files.createDirectories(directory);
        try (var files=Files.list(directory)) {
            return files.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".zip")).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
    }

    public synchronized void select(String name) { request(state.enabled(),name,false,true); }
    public synchronized void enabled(boolean enabled) { request(enabled,state.pack(),false,true); }
    public synchronized void reload() { request(state.enabled(),state.pack(),true,true); }

    private synchronized void request(boolean enabled,String name,boolean reload,boolean persist) {
        long revision=state.revision()+1;
        if (name==null || name.isEmpty()) enabled=false;
        final String selected=name==null?"":name;
        state=new Snapshot(revision,enabled,selected,enabled?Phase.COMPILING:Phase.OFF,"",null);
        if (persist) try { new ShaderSettings(enabled,selected).save(gameDir); }
        catch (IOException e) { state=new Snapshot(revision,false,selected,Phase.ERROR,e.getMessage(),null); return; }
        if (!enabled) return;
        compiler.execute(() -> {
            if(state.revision()!=revision) return;
            try {
                PackProgram program=compile.compile(gameDir,selected,reload);
                synchronized (this) { if (state.revision()==revision) state=new Snapshot(revision,true,selected,Phase.READY,"",program); }
            } catch (Exception e) { fail(revision,e); }
        });
    }

    public synchronized void phase(long revision,Phase phase) {
        Snapshot now=state;
        if (now.revision()==revision && now.enabled() && now.program()!=null && now.phase()!=phase)
            state=new Snapshot(revision,true,now.pack(),phase,"",now.program());
    }

    public synchronized void fail(long revision,Throwable error) {
        Snapshot now=state; if (now.revision()!=revision) return;
        String detail=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage();
        if (detail.length()>4000) detail=detail.substring(0,4000);
        state=new Snapshot(revision,false,now.pack(),Phase.ERROR,detail,null);
        siliconshaders.metal.Log.warn("[SiliconShaders shaders] "+detail);
        try { new ShaderSettings(false,now.pack()).save(gameDir); } catch (IOException ignored) { }
    }
}
