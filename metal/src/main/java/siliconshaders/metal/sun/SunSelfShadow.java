package siliconshaders.metal.sun;

import com.mojang.blaze3d.vertex.PoseStack;
import siliconshaders.metal.mixin.pack.SunFeatureDispatcherAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Only the first-person body needs extra preparation: Minecraft deliberately omits it from the camera pass.
 * Under Lunar Client its entity and feature hooks also reach an extra player submit, which then shows up as a visible,
 * lingering copy of the player in the world; there the body is posed and meshed by SunSelfShadowDirect instead, without a
 * submit. -Dsiliconshaders.sun.selfShadow=submit|direct|off overrides the choice (true = submit, false = off).
 */
public final class SunSelfShadow {
    public enum Mode { SUBMIT, DIRECT, OFF }
    public static final Mode MODE=mode();
    public static boolean preparing;
    private static FeatureRenderDispatcher source,owner,shadowDispatcher;
    private static StagedVertexBuffer staging;
    private static FeatureRenderDispatcher.PreparedFrame prepared;
    private static SubmitNodeStorage submits=new SubmitNodeStorage();
    private static final PoseStack pose=new PoseStack();
    private static int batchCount;
    private SunSelfShadow() {}

    private static Mode mode() {
        String forced=System.getProperty("siliconshaders.sun.selfShadow");
        if(forced!=null)return switch(forced.trim().toLowerCase(java.util.Locale.ROOT)){case "direct"->Mode.DIRECT;case "off","false"->Mode.OFF;default->Mode.SUBMIT;};
        if(!lunarClient())return Mode.SUBMIT;
        siliconshaders.metal.Log.info("[SiliconShaders sun] Lunar Client detected: first-person body shadow meshed directly, without an entity submit");
        return Mode.DIRECT;
    }

    /** Lunar's launcher classes, or its mixins merged into Minecraft's feature dispatcher. */
    static boolean lunarClient() {
        for(ClassLoader loader:new ClassLoader[]{SunSelfShadow.class.getClassLoader(),ClassLoader.getSystemClassLoader(),Thread.currentThread().getContextClassLoader()})
            if(loader!=null&&loader.getResource("com/moonsworth/lunar/genesis/Genesis.class")!=null)return true;
        try {
            for(var method:FeatureRenderDispatcher.class.getDeclaredMethods())if(method.getName().contains("lunar$"))return true;
        } catch(Throwable ignored) {}
        return false;
    }

    public static void source(FeatureRenderDispatcher dispatcher) { if(!preparing)source=dispatcher; }
    public static int batchCount() { return batchCount; }

    public static void prepare(CameraRenderState camera) {
        batchCount=0;finish();
        if(MODE==Mode.DIRECT){batchCount=SunSelfShadowDirect.prepare(camera);return;}
        var minecraft=Minecraft.getInstance();var player=minecraft.player;
        if(MODE==Mode.OFF||source==null||player==null||player.isSpectator()||player.isInvisible()
            ||minecraft.getCameraEntity()!=player||!minecraft.options.getCameraType().isFirstPerson())return;
        if(shadowDispatcher==null||source!=owner) {
            if(shadowDispatcher!=null){shadowDispatcher.close();staging.close();}
            var access=(SunFeatureDispatcherAccess)(Object)source;
            shadowDispatcher=new FeatureRenderDispatcher(minecraft.gameRenderer.renderBuffers(),access.siliconshaders$sunModels(),access.siliconshaders$sunAtlases(),access.siliconshaders$sunFont(),access.siliconshaders$sunGameState());
            // A tiny independent upload pool; sharing the world's staged buffer would overwrite already prepared models.
            staging=new StagedVertexBuffer(()->"siliconshaders first-person shadow body",8192);
            ((SunFeatureDispatcherAccess)(Object)shadowDispatcher).siliconshaders$sunStaging(staging);owner=source;
        }
        int before=SunEntities.count();preparing=true;
        try {
            var dispatcher=minecraft.getEntityRenderDispatcher();
            var state=dispatcher.extractEntity(player,minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false));
            dispatcher.submit(state,camera,state.x-camera.pos.x,state.y-camera.pos.y,state.z-camera.pos.z,pose,submits);
            prepared=shadowDispatcher.prepareFrame(submits);
            batchCount=SunEntities.count()-before;
        } catch(RuntimeException failure) {
            if(prepared!=null){prepared.close();prepared=null;}
            shadowDispatcher.close();staging.close();shadowDispatcher=null;owner=null;submits=new SubmitNodeStorage();throw failure;
        } finally { preparing=false;SunEntities.collecting=false; }
    }

    /** Recycle the preceding frame before preparing the next one, outside any open render pass.
     * endFrame inserts GPU fences; it cannot run from the terrain's shadow draw callback. */
    public static void finish() {
        if(prepared!=null) {
            prepared.close();prepared=null;staging.endFrame();
        }
    }
}
