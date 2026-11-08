package siliconshaders.metal.camera;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/** Compose once after water and before the hand's depth clear; hands and HUD stay sharp. */
public final class CameraRuntime {
    public static final int BYTES=432;
    private static final CameraHistory HISTORY=new CameraHistory();
    private static final Matrix4f INVERSE=new Matrix4f(),PREVIOUS=new Matrix4f();
    private static final Vector4f LIGHT=new Vector4f();
    private static boolean prepared,historyValid,failed,used;private static float dt,time,lightStrength;private static long last;
    private static float[] tuning;private static float medium;
    private CameraRuntime(){}
    public static void prepare(Matrix4fc clip,CameraRenderState camera,boolean shaderOn,float sunAngle,float rain,boolean celestial){
        var mc=Minecraft.getInstance();var settings=CameraSettings.get();
        if(!shaderOn||settings.mask()==0||mc.level==null||failed){prepared=false;HISTORY.reset();last=0;return;}
        var target=mc.gameRenderer.mainRenderTarget();var color=target.getColorTexture();if(color==null){prepared=false;return;}
        long now=System.nanoTime();dt=last==0?0:(float)Math.clamp((now-last)*1e-9,0,.25);last=now;
        historyValid=HISTORY.update(clip,camera.pos.x,camera.pos.y,camera.pos.z,mc.level,color.getWidth(0),color.getHeight(0),now,mc.isPaused());
        INVERSE.set(HISTORY.inverse());PREVIOUS.set(HISTORY.reprojection());
        tuning=settings.published();
        medium=camera.fogType==net.minecraft.world.level.material.FogType.WATER?1:
            camera.fogType==net.minecraft.world.level.material.FogType.LAVA||camera.fogType==net.minecraft.world.level.material.FogType.POWDER_SNOW?-1:0;
        float sx=-(float)Math.sin(sunAngle),sy=(float)Math.cos(sunAngle),moon=sy<0?1:0;
        if(moon>0){sx=-sx;sy=-sy;}
        LIGHT.set(sx,sy,0,0);clip.transform(LIGHT);
        lightStrength=LIGHT.w>1e-5f?Math.clamp((sy-.03f)/.15f,0,1)*Math.clamp(rain,0,1)*(moon>0?(tuning[CameraSettings.FLARE_MOON]>.5f?.06f:0):1):0;
        if(!celestial||!Float.isFinite(lightStrength+LIGHT.x+LIGHT.y+LIGHT.w)){LIGHT.set(-10,-10,0,0);lightStrength=0;}
        if(LIGHT.w>1e-5f){LIGHT.x=LIGHT.x/LIGHT.w*.5f+.5f;LIGHT.y=LIGHT.y/LIGHT.w*.5f+.5f;}else{LIGHT.x=LIGHT.y=-10;}
        if(mc.isPaused())dt=0;else time+=dt;
        prepared=INVERSE.isFinite()&&PREVIOUS.isFinite();
    }
    public static boolean needsFinish(){return prepared||used;}
    public static boolean packet(long p){
        MemoryUtil.memSet(p,0,BYTES);
        if(!prepared||CameraSettings.get().mask()==0)return false;
        INVERSE.getToAddress(p);PREVIOUS.getToAddress(p+64);
        MemoryUtil.memPutFloat(p+136,dt); // screen.z; native fills width, height, transfer function
        MemoryUtil.memPutFloat(p+144,LIGHT.x);MemoryUtil.memPutFloat(p+148,LIGHT.y);MemoryUtil.memPutFloat(p+152,lightStrength);MemoryUtil.memPutFloat(p+156,historyValid?1:0);
        MemoryUtil.memPutFloat(p+160,1);MemoryUtil.memPutFloat(p+164,.9f);MemoryUtil.memPutFloat(p+168,.75f);MemoryUtil.memPutFloat(p+172,time);
        for(int i=0;i<tuning.length;i++)MemoryUtil.memPutFloat(p+176+4L*i,tuning[i]);
        MemoryUtil.memPutFloat(p+412,medium);
        MemoryUtil.memPutFloat(p+416,HISTORY.direction().x());MemoryUtil.memPutFloat(p+420,HISTORY.direction().y());MemoryUtil.memPutFloat(p+424,HISTORY.direction().z());MemoryUtil.memPutFloat(p+428,HISTORY.moving()?1:0);return true;
    }
    public static void used(boolean active){used=active;}
    public static void finish(RenderTarget target){if(siliconshaders.metal.own.RenderScale.hdrActive())return;var own=siliconshaders.metal.own.OwnTerrain.get();if(own!=null&&needsFinish())own.finishCamera(target);}
    public static void failed(RuntimeException failure){failed=true;prepared=false;siliconshaders.metal.Log.warn("[SiliconShaders camera] Effects disabled until shader restart: "+failure.getMessage());}
    public static void close(){prepared=used=failed=false;tuning=null;last=0;time=0;HISTORY.reset();}
}
