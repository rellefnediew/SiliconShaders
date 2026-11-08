package siliconshaders.metal.sun;

import com.mojang.blaze3d.vertex.PoseStack;
import siliconshaders.metal.mixin.pack.SunFeatureDispatcherAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Only the first-person body needs extra preparation: Minecraft deliberately omits it from the camera pass. */
public final class SunSelfShadow {
    public static boolean preparing;
    private static FeatureRenderDispatcher source,owner,shadowDispatcher;
    private static StagedVertexBuffer staging;
    private static FeatureRenderDispatcher.PreparedFrame prepared;
    private static SubmitNodeStorage submits=new SubmitNodeStorage();
    private static final PoseStack pose=new PoseStack();
    private static int batchCount;
    private SunSelfShadow() {}

    public static void source(FeatureRenderDispatcher dispatcher) { if(!preparing)source=dispatcher; }
    public static int batchCount() { return batchCount; }

    public static void prepare(CameraRenderState camera) {
        batchCount=0;finish();var minecraft=Minecraft.getInstance();var player=minecraft.player;
        if(source==null||player==null||player.isSpectator()||player.isInvisible()
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
