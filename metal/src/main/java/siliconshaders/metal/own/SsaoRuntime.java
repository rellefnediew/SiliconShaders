package siliconshaders.metal.own;

import com.mojang.blaze3d.pipeline.RenderTarget;
import siliconshaders.metal.sun.SunTuning;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Screen-space contacts after opaque objects and before classic transparency. No world scan or readback. */
public final class SsaoRuntime {
    public static final int BYTES=112;
    private static final Matrix4f INVERSE=new Matrix4f();
    private static boolean prepared,consumed,failed,used;
    private static float strength,radius,quality,range;
    private static final int ON=SunTuning.index("ssao"),STRENGTH=SunTuning.index("ssaoStrength"),
        RADIUS=SunTuning.index("ssaoRadius"),QUALITY=SunTuning.index("ssaoQuality");
    private SsaoRuntime(){}
    public static void begin(){prepared=consumed=false;}
    public static void prepare(Matrix4fc clip,CameraRenderState camera,boolean shaderOn){
        prepared=used;strength=0;
        var mc=Minecraft.getInstance();var tuning=SunTuning.get();
        if(failed||!shaderOn||mc.level==null
            ||mc.options.improvedTransparency().get())return;
        strength=tuning.value(ON)>.5f?tuning.value(STRENGTH):0;
        radius=tuning.value(RADIUS);quality=tuning.value(QUALITY);
        range=Math.min(96,Math.max(8,camera.fogData.renderDistanceEnd*.75f));
        INVERSE.set(clip).invert();prepared=INVERSE.isFinite()&&Float.isFinite(range);
    }
    static void packet(long p){
        MemoryUtil.memSet(p,0,BYTES);if(!prepared)return;
        INVERSE.getToAddress(p);
        MemoryUtil.memPutFloat(p+80,radius);MemoryUtil.memPutFloat(p+84,strength);
        MemoryUtil.memPutFloat(p+88,quality);MemoryUtil.memPutFloat(p+92,range);
    }
    public static void finish(RenderTarget target){
        if(!prepared||consumed)return;consumed=true;used=strength>0;
        var own=OwnTerrain.get();if(own!=null)own.finishSsao(target);
    }
    static void failed(RuntimeException failure){failed=true;prepared=false;siliconshaders.metal.Log.warn("[SiliconShaders SSAO] Disabled until renderer reload: "+failure.getMessage());}
    public static void close(){prepared=consumed=failed=used=false;}
}
