package siliconshaders.metal.own;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import siliconshaders.metal.sun.SunTuning;
import net.minecraft.client.Minecraft;

/** World-only target routing for render scale and switchable HDR bloom; hand/HUD remain on the main target. */
public final class RenderScale {
    private static final float[] FACTORS={1f,.85f,.67f,.5f};
    private static TextureTarget scaled,hdrWorld,toneMapped;
    private static RenderTarget world,main;
    private static boolean active,hdr,failed;
    private static long scaledFrames,hdrFrames,upscaleFrames;
    public static long[] benchmarkCounters(){return new long[]{scaledFrames,hdrFrames,upscaleFrames};}
    public static java.util.Map<String,Object> benchmarkStatus(){
        var own=OwnTerrain.get();return java.util.Map.of("failedUntilRestart",failed,"metalFxSupported",own!=null&&own.upscaleSupported(),
            "worldWidth",world==null?0:world.width,"worldHeight",world==null?0:world.height);
    }
    private RenderScale(){}
    public static float factor(int setting){return FACTORS[Math.clamp(setting,0,FACTORS.length-1)];}
    public static boolean hdrActive(){return active&&hdr;}
    public static boolean isHdrTarget(com.mojang.renderpearl.api.textures.GpuTexture texture){return active&&hdr&&world!=null&&world.getColorTexture()==texture;}
    public static boolean wantsHdr(){
        var settings=siliconshaders.metal.sun.SunSettings.get().state();
        return settings.enabled()&&settings.gbuffer()&&settings.gbufferView()==0&&siliconshaders.metal.camera.CameraSettings.get().hdrBloom();
    }
    private static TextureTarget target(TextureTarget previous,String name,int w,int h,GpuFormat format){return target(previous,name,w,h,format,true);}
    private static TextureTarget target(TextureTarget previous,String name,int w,int h,GpuFormat format,boolean depth){
        if(previous==null)return new TextureTarget(name,w,h,format,depth?GpuFormat.D32_FLOAT:null);
        if(previous.width!=w||previous.height!=h)previous.resize(w,h);return previous;
    }
    public static void begin(RenderTarget full){
        active=hdr=false;world=null;
        OwnTerrain own=OwnTerrain.get();if(own==null)return;
        if(failed||Minecraft.getInstance().level==null||full==null||full.width<16||full.height<16){own.hdrMode(false);return;}
        float factor=factor(Math.round(SunTuning.get().value(SunTuning.RENDER_SCALE)));
        if(factor<1&&!own.upscaleSupported())factor=1;
        boolean nextHdr=wantsHdr();if(factor>=1&&!nextHdr){own.hdrMode(false);return;}
        int width=Math.max(8,Math.round(full.width*factor)),height=Math.max(8,Math.round(full.height*factor));
        // RGB11/11/10 float keeps HDR range in four bytes per pixel, like the old RGBA8 world image; alpha is opaque.
        try {
        if(nextHdr){hdrWorld=target(hdrWorld,"siliconshaders HDR world",width,height,GpuFormat.RG11B10_FLOAT);world=hdrWorld;}
        else {scaled=target(scaled,"siliconshaders world (render scale)",width,height,GpuFormat.RGBA8_UNORM);world=scaled;}
        main=full;hdr=nextHdr;active=true;own.hdrMode(hdr);
        if(factor<1)scaledFrames++;if(hdr)hdrFrames++;
        }catch(RuntimeException failure){failed=true;active=hdr=false;own.hdrMode(false);siliconshaders.metal.Log.warn("[SiliconShaders world target] Allocation failed; using the original target: "+failure.getMessage());}
    }
    public static RenderTarget redirect(){return active?world:null;}
    public static void finish(){
        if(!active)return;
        OwnTerrain own=OwnTerrain.get();
        try {
            if(own==null)throw new IllegalStateException("World renderer unavailable");
            RenderTarget output=world;
            if(hdr){
                output=main;
                if(world.width!=main.width||world.height!=main.height){toneMapped=target(toneMapped,"siliconshaders HDR display image",world.width,world.height,GpuFormat.RGBA8_UNORM,false);output=toneMapped;}
                own.finishCameraHdr(world,output);
            }
            active=false;
            if(output!=main){if(!own.upscale(output,main))throw new IllegalStateException("Render scale unavailable");upscaleFrames++;}
        }catch(RuntimeException failure){failed=true;siliconshaders.metal.Log.warn("[SiliconShaders world target] Disabled until restart: "+failure.getMessage());}
        finally {active=false;}
    }
    public static void close(){active=hdr=failed=false;world=main=null;for(TextureTarget t:new TextureTarget[]{scaled,hdrWorld,toneMapped})if(t!=null)t.destroyBuffers();scaled=hdrWorld=toneMapped=null;}
}
