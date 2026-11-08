package siliconshaders.metal.own;

import com.mojang.blaze3d.pipeline.RenderTarget;
import siliconshaders.metal.sun.SunSettings;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryUtil;

/** Native water only; no shaderpack runtime, game input or world traversal. */
public final class WaterRuntime {
    static final int BYTES=288;
    private static final boolean ON=Boolean.parseBoolean(System.getProperty("siliconshaders.sun.water","true"));
    private static volatile boolean available,failed;
    private record FogTint(float r,float g,float b) {}
    private static final FogTint DEFAULT_TINT=new FogTint(.02f,.08f,.14f);
    private static volatile FogTint fogTint=DEFAULT_TINT;
    private static final Submersion submersion=new Submersion();
    private static java.lang.ref.WeakReference<Object> world=new java.lang.ref.WeakReference<>(null);
    static final class Submersion {
        private long previous;private boolean wasInside;float wet,pulse;
        void update(long now,boolean paused,boolean inside) {
            float dt=previous==0?0:(float)Math.clamp((now-previous)*1e-9,0,.25);previous=now;
            if(!paused) {
                if(inside&&!wasInside)pulse=1;
                wet=inside?1:wet*(float)Math.exp(-dt/.8);pulse*=(float)Math.exp(-dt/.4);
            }
            wasInside=inside;
        }
        void reset() {previous=0;wet=pulse=0;wasInside=false;}
    }
    private WaterRuntime() {}
    public static boolean enabled() { return ON&&!failed&&OwnMesher.ON&&OwnTerrain.TRANSLUCENT&&SunSettings.get().state().enabled()
            &&!net.minecraft.client.Minecraft.getInstance().options.improvedTransparency().get()
            &&net.minecraft.client.Minecraft.getInstance().level!=null
            &&!net.minecraft.client.Minecraft.getInstance().level.dimensionType().hasCeiling()
            &&siliconshaders.metal.sun.SunDimension.supported(net.minecraft.client.Minecraft.getInstance().level.dimensionType().skybox(),false); }
    public static boolean replacesFog() { return available&&enabled(); }
    public static void captureFog(org.joml.Vector3fc color) {
        if(Float.isFinite(color.x())&&Float.isFinite(color.y())&&Float.isFinite(color.z()))
            fogTint=new FogTint(Math.clamp(color.x(),0,1),Math.clamp(color.y(),0,1),Math.clamp(color.z(),0,1));
    }
    static void writeFogColor(long address,FogData fog,boolean inside) {
        if(!inside){fog.color.getToAddress(address);return;}
        FogTint tint=fogTint;
        MemoryUtil.memPutFloat(address,tint.r());MemoryUtil.memPutFloat(address+4,tint.g());
        MemoryUtil.memPutFloat(address+8,tint.b());MemoryUtil.memPutFloat(address+12,1);
    }
    static void available(boolean value) { available=value; }
    static void packet(long p,Matrix4fc view,CameraRenderState camera,float time,float weather,float sx,float sy,float sz,float sunlight,float[] tuning) {
        MemoryUtil.memSet(p,0,BYTES);if(!enabled())return;view.getToAddress(p);new org.joml.Matrix4f(view).invert().getToAddress(p+64);
        MemoryUtil.memPutFloat(p+128,sx);MemoryUtil.memPutFloat(p+132,sy);MemoryUtil.memPutFloat(p+136,sz);MemoryUtil.memPutFloat(p+140,sunlight);
        boolean inside=camera.fogType==FogType.WATER;
        writeFogColor(p+144,camera.fogData,inside);
        Object current=net.minecraft.client.Minecraft.getInstance().level;
        if(world.get()!=current){world=new java.lang.ref.WeakReference<>(current);submersion.reset();available=false;}
        submersion.update(System.nanoTime(),net.minecraft.client.Minecraft.getInstance().isPaused(),inside);
        MemoryUtil.memPutFloat(p+160,time);MemoryUtil.memPutFloat(p+164,inside?1:0);MemoryUtil.memPutFloat(p+168,submersion.wet);
        MemoryUtil.memPutFloat(p+172,inside&&camera.fogData.environmentalEnd>100000?1:0);
        MemoryUtil.memPutFloat(p+176,(float)(camera.pos.x-Math.floor(camera.pos.x/4096)*4096));
        MemoryUtil.memPutFloat(p+180,(float)(camera.pos.y-Math.floor(camera.pos.y/4096)*4096));
        MemoryUtil.memPutFloat(p+184,(float)(camera.pos.z-Math.floor(camera.pos.z/4096)*4096));
        MemoryUtil.memPutFloat(p+200,tuning[siliconshaders.metal.sun.SunTuning.WATER_START+6]);MemoryUtil.memPutFloat(p+204,enabled()?1:0);
        MemoryUtil.memPutFloat(p+208,weather);MemoryUtil.memPutFloat(p+212,tuning[siliconshaders.metal.sun.SunTuning.WATER_START+12]);MemoryUtil.memPutFloat(p+216,submersion.pulse);
        for(int i=0;i<12;i++)MemoryUtil.memPutFloat(p+224+4L*i,tuning[siliconshaders.metal.sun.SunTuning.WATER_START+i]);
        // camera.w: the reflection edge fade; the loop's last slot (268) is overwritten with runtime data below.
        MemoryUtil.memPutFloat(p+188,tuning[siliconshaders.metal.sun.SunTuning.WATER_START+11]);
        // Last WaterFrame slot is runtime data: match the visible scene's view-depth range.
        float renderEnd=camera.fogData.renderDistanceEnd;
        MemoryUtil.memPutFloat(p+268,Float.isFinite(renderEnd)?Math.max(16,renderEnd):64);
        // quality: reflection search and underwater pass presets (0 = full quality).
        MemoryUtil.memPutFloat(p+272,tuning[siliconshaders.metal.sun.SunTuning.WATER_REFLECTION_QUALITY]);MemoryUtil.memPutFloat(p+276,tuning[siliconshaders.metal.sun.SunTuning.UNDERWATER_QUALITY]);
        MemoryUtil.memPutFloat(p+284,tuning[siliconshaders.metal.sun.SunTuning.UNDERWATER_HALF]);
    }
    /**
     * Weather for the water surface, in WaterFrame's free quality.z (zero: as before): how much rain and thunder take from
     * the water's own color (its share greys it, as the ambient light).
     */
    static void weather(long p,float dim) {
        if(!enabled())return;
        MemoryUtil.memPutFloat(p+280,Float.isFinite(dim)?Math.max(0,Math.min(1,dim)):0);
    }
    public static void finish(RenderTarget target) {
        OwnTerrain own=OwnTerrain.get();
        if(own!=null&&enabled())own.finishWater(target);
    }
    static void failed(RuntimeException failure) {available=false;failed=true;siliconshaders.metal.Log.warn("[SiliconShaders water] Disabled until the shader is toggled: "+failure.getMessage());}
    public static void close() { failed=false;available=false;fogTint=DEFAULT_TINT;submersion.reset();world=new java.lang.ref.WeakReference<>(null); }
}
