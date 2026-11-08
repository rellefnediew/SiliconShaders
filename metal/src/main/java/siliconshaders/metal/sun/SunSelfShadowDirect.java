package siliconshaders.metal.sun;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import siliconshaders.metal.MetalBridge;
import siliconshaders.metal.mixin.pack.SunLivingPoseAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.entity.Pose;
import org.lwjgl.system.MemoryUtil;

/**
 * The first-person body for the sun shadow, posed and meshed by the mod itself. The usual path (an entity submit and a
 * prepared feature frame) is shared with Lunar Client's entity and feature hooks, which turn that extra player into a
 * visible, lingering copy in the world. Here nothing is submitted: the player's own model is posed exactly as
 * LivingEntityRenderer.submit poses it and written straight into a small buffer of the mod's own. Only the body casts this
 * way (no armor, held items or cosmetics). Render thread only.
 */
public final class SunSelfShadowDirect {
    /** Position (3 floats), UV0 (2 floats), color (RGBA8): 24 bytes, as SunEntities expects them. */
    static final int STRIDE=24,UV=12,COLOR=20;
    private static final int MAX_VERTICES=4096,RING=4;
    private static final GpuBuffer[] vertices=new GpuBuffer[RING];
    private static GpuBuffer indices;
    private static int ring,vertexCount;
    private static long scratch;
    private static final Capture capture=new Capture();
    private SunSelfShadowDirect() {}

    /** One body per frame, appended to SunEntities' casters. Returns the number of casters added (0 or 1). */
    static int prepare(CameraRenderState camera) {
        var minecraft=Minecraft.getInstance();var player=minecraft.player;
        if(player==null||player.isSpectator()||player.isInvisible()||minecraft.getCameraEntity()!=player||!minecraft.options.getCameraType().isFirstPerson())return 0;
        var dispatcher=minecraft.getEntityRenderDispatcher();
        if(!(dispatcher.extractEntity(player,minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(false)) instanceof AvatarRenderState state))return 0;
        if(state.hasPose(Pose.SLEEPING)||state.isInvisible)return 0;
        var renderer=dispatcher.getRenderer(state);if(renderer==null)return 0;
        var texture=minecraft.getTextureManager().getTexture(renderer.getTextureLocation(state));
        GpuTextureView view=texture==null?null:texture.getTextureView();GpuSampler sampler=texture==null?null:texture.getSampler();
        if(view==null||sampler==null)return 0;
        if(!buffers())return 0;
        // LivingEntityRenderer.submit's pose, after EntityRenderDispatcher's camera-relative translation and render offset.
        var pose=new PoseStack();
        var offset=renderer.getRenderOffset(state);
        pose.translate(state.x-camera.pos.x+offset.x,state.y-camera.pos.y+offset.y,state.z-camera.pos.z+offset.z);
        pose.scale(state.scale,state.scale,state.scale);
        var access=(SunLivingPoseAccess)(Object)renderer;
        access.siliconshaders$sunSetupRotations(state,pose,state.bodyRot,state.scale);
        pose.scale(-1,-1,1);
        access.siliconshaders$sunScale(state,pose);
        pose.translate(0,-1.501f,0);
        var model=renderer.getModel();
        model.setupAnim(state);
        capture.reset(scratch);
        model.renderToBuffer(pose,capture,0xF000F0,0,-1);
        vertexCount=capture.count;
        if(vertexCount<4||capture.overflow)return 0;
        GpuBuffer target=vertices[ring];ring=(ring+1)%RING;if(MetalBridge.bufferAddress(target)==0)return 0;
        MemoryUtil.memCopy(scratch,MetalBridge.bufferAddress(target),(long)vertexCount*STRIDE);
        SunEntities.addDirect(target,indices,view,sampler,vertexCount,vertexCount/4*6);
        return 1;
    }

    /** A ring of vertex buffers, so the GPU can still read earlier frames' bodies while this one is written, and one shared quad index list. */
    private static boolean buffers() {
        if(indices!=null)return true;
        var device=RenderSystem.getDevice();
        scratch=MemoryUtil.nmemAlloc((long)MAX_VERTICES*STRIDE);if(scratch==0)return false;
        for(int i=0;i<RING;i++)vertices[i]=device.createBuffer(()->"siliconshaders first-person shadow body",GpuBuffer.USAGE_VERTEX|GpuBuffer.USAGE_MAP_WRITE,(long)MAX_VERTICES*STRIDE);
        var quads=MemoryUtil.memAlloc(MAX_VERTICES/4*6*4);
        for(int q=0;q<MAX_VERTICES/4;q++){int v=q*4;quads.putInt(v).putInt(v+1).putInt(v+2).putInt(v+2).putInt(v+3).putInt(v);}
        quads.flip();
        try { indices=device.createBuffer(()->"siliconshaders first-person shadow body quads",GpuBuffer.USAGE_INDEX,quads); }
        finally { MemoryUtil.memFree(quads); }
        return true;
    }

    /** Writes position, UV and color of each vertex the model emits; normals, light and overlay are not needed for shadows. */
    private static final class Capture implements VertexConsumer {
        long base;int count;boolean overflow;
        void reset(long address){base=address;count=0;overflow=false;}
        private long at(){return base+(long)(count-1)*STRIDE;}
        @Override public VertexConsumer addVertex(float x,float y,float z){
            if(count>=MAX_VERTICES){overflow=true;return this;}
            count++;long p=at();MemoryUtil.memPutFloat(p,x);MemoryUtil.memPutFloat(p+4,y);MemoryUtil.memPutFloat(p+8,z);
            MemoryUtil.memPutFloat(p+UV,0);MemoryUtil.memPutFloat(p+UV+4,0);MemoryUtil.memPutInt(p+COLOR,-1);return this;
        }
        @Override public VertexConsumer setColor(int r,int g,int b,int a){if(count>0&&!overflow)MemoryUtil.memPutInt(at()+COLOR,(a<<24)|(b<<16)|(g<<8)|r);return this;}
        @Override public VertexConsumer setColor(int argb){if(count>0&&!overflow)MemoryUtil.memPutInt(at()+COLOR,(argb&0xFF00FF00)|((argb>>16)&0xFF)|((argb&0xFF)<<16));return this;}
        @Override public VertexConsumer setUv(float u,float v){if(count>0&&!overflow){MemoryUtil.memPutFloat(at()+UV,u);MemoryUtil.memPutFloat(at()+UV+4,v);}return this;}
        @Override public VertexConsumer setUv1(int u,int v){return this;}
        @Override public VertexConsumer setUv2(int u,int v){return this;}
        @Override public VertexConsumer setUv3(float u,float v){return this;}
        @Override public VertexConsumer setNormal(float x,float y,float z){return this;}
        @Override public VertexConsumer setLineWidth(float width){return this;}
    }
}
