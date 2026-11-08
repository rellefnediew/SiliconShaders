package siliconshaders.metal.fast;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * OptiFine's Smart Leaves: cutout ("fancy") leaves without the faces between two leaf blocks. Vanilla drops those faces only
 * with cutout leaves off, and then draws the leaves opaque; with them on, every inner face stays. On Apple's tile-based GPUs an
 * alpha-tested face is shaded before hidden-surface removal can drop it, so a canopy costs one shaded leaf layer per block the
 * view crosses, and every one of those quads is vertex and tiler work too. Here the face is dropped once, on the section
 * worker, when the section is meshed (LeavesBlock.skipRendering, which every mesh path reaches through Block.shouldRenderFace:
 * vanilla's, ours, Fabric API's), so the GPU only gets the canopy's shell; nothing runs per frame.
 * Modes: ALL drops faces between any two leaf blocks (Cull Leaves' rule, the most culled), SAME only between identical leaf
 * blocks (OptiFine's: where two kinds meet, both faces stay). -Dsiliconshaders.fast.smartLeaves=false keeps vanilla's leaves.
 */
public final class SmartLeaves {
    public enum Mode { OFF, ALL, SAME }
    public static final boolean ON=!"false".equals(System.getProperty("siliconshaders.fast.smartLeaves"));
    private static final Gson JSON=new Gson();
    private static class Holder { static final SmartLeaves VALUE=new SmartLeaves(FabricLoader.getInstance().getGameDir()); }
    public static SmartLeaves get() { return Holder.VALUE; }
    // Read by the section workers on every leaf face: a plain static, written on the render thread before allChanged()
    // queues the rebuild (the task hand-off orders the write before the workers' reads). Its first value is the saved
    // mode, read when the first leaf face is meshed (outside the game, as in the checks: off).
    private static Mode active=Mode.OFF;
    static {
        try { get().apply(); } catch(Throwable noGame) { active=Mode.OFF; }
    }

    private final Path file;
    private Mode mode=Mode.ALL;
    public SmartLeaves(Path directory) {
        file=directory.resolve("config/siliconshaders-smart-leaves.json");
        try {
            if(Files.isRegularFile(file)) {
                if(Files.size(file)>1024)throw new IllegalArgumentException("Oversized smart leaves settings");
                JsonObject object=JSON.fromJson(Files.readString(file),JsonObject.class);
                if(object!=null&&object.has("mode"))mode=Mode.valueOf(object.get("mode").getAsString().toUpperCase(Locale.ROOT));
            }
        } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders] Smart leaves settings ignored: "+failure.getMessage()); }
    }
    public synchronized Mode mode() { return mode; }
    /** The menu's button: off, all leaves, same kind, off. */
    public synchronized void next() { set(Mode.values()[(mode.ordinal()+1)%Mode.values().length]); }
    public synchronized void set(Mode next) { if(choose(next))rebuild(); }
    /** Saves next and makes it the mesher's; true when sections must be meshed again. */
    synchronized boolean choose(Mode next) { mode=next;save();return apply(); }
    /** Makes this instance's mode the one the mesher uses; true when that changed it. */
    synchronized boolean apply() {
        Mode next=ON?mode:Mode.OFF;
        if(active==next)return false;
        active=next;return true;
    }
    private static void rebuild() {
        Minecraft minecraft=Minecraft.getInstance();
        if(minecraft!=null&&minecraft.level!=null)minecraft.levelExtractor.allChanged();
    }
    private void save() {
        JsonObject object=new JsonObject();object.addProperty("mode",mode.name().toLowerCase(Locale.ROOT));
        try {
            Files.createDirectories(file.getParent());Path temporary=Files.createTempFile(file.getParent(),"smart-leaves-",".tmp");
            try { Files.writeString(temporary,JSON.toJson(object)+"\n");Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(temporary); }
        } catch(Exception failure) { siliconshaders.metal.Log.warn("[SiliconShaders] Smart leaves settings not saved: "+failure.getMessage()); }
    }

    /** True: the leaf face of state toward neighbor is dropped (state is a leaf block, from LeavesBlock.skipRendering). */
    public static boolean skip(BlockState state,BlockState neighbor) {
        Mode mode=active;
        if(mode==Mode.ALL)return neighbor.getBlock() instanceof LeavesBlock;
        return mode==Mode.SAME&&neighbor.getBlock()==state.getBlock();
    }
}
